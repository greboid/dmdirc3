package com.dmdirc

import com.dmdirc.ktirc.IrcClient
import com.dmdirc.ktirc.events.BatchReceived
import com.dmdirc.ktirc.events.ChannelJoined
import com.dmdirc.ktirc.events.ChannelParted
import com.dmdirc.ktirc.events.IrcEvent
import com.dmdirc.ktirc.events.NicknameChangeFailed
import com.dmdirc.ktirc.events.NoticeReceived
import com.dmdirc.ktirc.events.ServerDisconnected
import com.dmdirc.ktirc.events.ServerReady
import com.dmdirc.ktirc.events.TargetedEvent
import com.dmdirc.ktirc.io.CaseMapping
import com.dmdirc.ktirc.messages.sendAction
import com.dmdirc.ktirc.messages.sendAway
import com.dmdirc.ktirc.messages.sendJoin
import com.dmdirc.ktirc.messages.sendMessage
import com.dmdirc.ktirc.messages.sendNickChange
import com.dmdirc.ktirc.messages.sendPart
import com.dmdirc.ktirc.model.ChannelUser
import com.dmdirc.ktirc.model.ServerFeature
import com.dmdirc.ktirc.model.ServerStatus
import javafx.beans.property.Property
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.ObservableSet
import javafx.scene.Node
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.atomic.AtomicLong

private val connectionCounter = AtomicLong(0)

object ConnectionContract {
    interface Controller {
        val children: Connection.WindowMap
        val connected: Property<Boolean>
        val model: WindowModel
        var networkName: String
        fun connect()
        fun sendMessage(channel: String, message: String)
        fun sendAction(channel: String, action: String)
        fun sendAway(message: String? = null)
        fun joinChannel(channel: String)
        fun leaveChannel(channel: String)
        fun getUsers(channel: String): Iterable<ChannelUser>
        fun disconnect()
        fun notify(window: WindowModel, message: String)
        fun addEventListener(listener: (IrcEvent) -> Unit)
        fun removeEventListener(listener: (IrcEvent) -> Unit)
    }
}

class Connection(
    private val connectionDetails: ConnectionDetails,
    private val config1: ClientConfig,
    private val notificationManager: NotificationManager,
    private val windowFactory: (WindowModel) -> WindowUI
) : ConnectionContract.Controller {

    private val listeners = Collections.synchronizedList(mutableListOf<(IrcEvent) -> Unit>())

    private val client: IrcClient = IrcClient {
        server {
            host = connectionDetails.hostname
            port = connectionDetails.port
            password = connectionDetails.password
            useTls = connectionDetails.tls
        }
        profile {
            nickname = config1[ClientSpec.DefaultProfile.nickname]
            realName = config1[ClientSpec.DefaultProfile.realname]
            username = config1[ClientSpec.DefaultProfile.username]
        }
        behaviour {
            alwaysEchoMessages = true
        }
    }

    private val connectionId = connectionCounter.incrementAndGet().toString(16).padStart(20)

    private val eventMapper = IrcEventMapper(client)

    override val model =
        WindowModel(connectionDetails.hostname, WindowType.SERVER, this, eventMapper, config1, connectionId)

    override val connected = SimpleBooleanProperty(false).threadAsserting()

    override val children = WindowMap { client.caseMapping }.apply {
        this += Child(model, windowFactory(model))
    }

    override var networkName = ""

    init {
        client.onEvent(this::handleEvent)
    }

    override fun connect() {
        client.connect()
    }

    override fun sendMessage(channel: String, message: String) {
        client.sendMessage(channel, message)
    }

    override fun sendAction(channel: String, action: String) {
        client.sendAction(channel, action)
    }

    override fun sendAway(message: String?) {
        client.sendAway(message)
    }

    override fun joinChannel(channel: String) {
        client.sendJoin(channel)
    }

    override fun leaveChannel(channel: String) {
        client.sendPart(channel)
    }

    override fun getUsers(channel: String): Iterable<ChannelUser> = client.channelState[channel]?.users ?: emptyList()

    override fun notify(window: WindowModel, message: String) = notificationManager.notify(window, message)

    override fun addEventListener(listener: (IrcEvent) -> Unit) {
        listeners += listener
    }

    override fun removeEventListener(listener: (IrcEvent) -> Unit) {
        listeners -= listener
    }

    private fun handleEvent(event: IrcEvent) {
        listeners.forEach { it(event) }

        when {
            event is BatchReceived -> event.events.forEach(this::handleEvent)
            event is ServerReady -> {
                connectionDetails.autoJoin.forEach { joinChannel(it) }
                runLater {
                    connected.value = true
                    networkName = client.serverState.features[ServerFeature.Network] ?: ""
                    model.name.value = client.serverState.serverName
                    model.title.value = "${model.name.value} [${model.connection?.networkName ?: ""}]"
                }
            }
            event is ServerDisconnected -> runLater { connected.value = false }
            event is ChannelJoined && client.isLocalUser(event.user) -> runLater {
                if (!children.contains(event.target)) {
                    val model = WindowModel(event.target, WindowType.CHANNEL, this, eventMapper, config1, connectionId)
                    model.addImageHandler(config1)
                    children += Child(model, windowFactory(model))
                }
            }
            event is ChannelParted && client.isLocalUser(event.user) -> runLater { children -= event.target }
        }

        runLater {
            if (event is TargetedEvent) {
                if (client.isLocalUser(event.target) || event.target == "*") {
                    handleOwnEvent(event)
                } else {
                    windowModel(event.target)?.handleEvent(event)
                }
            } else if (event is NicknameChangeFailed && client.serverState.status < ServerStatus.Ready) {
                client.sendNickChange(client.localUser.nickname + (0..9).random())
            } else {
                model.handleEvent(event)
            }
        }
    }

    private fun handleOwnEvent(event: TargetedEvent) {
        when (event) {
            is NoticeReceived -> model.handleEvent(event)
        }
    }

    private fun windowModel(windowName: String) = children[windowName]?.model

    override fun disconnect() {
        client.disconnect()
    }

    data class Child(val model: WindowModel, val ui: Node)

    class WindowMap(private val caseMappingProvider: () -> CaseMapping) : Iterable<Child> {

        private val values = mutableSetOf<Child>().observable().synchronized()
        val observable: ObservableSet<Child> = values.readOnly()

        operator fun get(name: String) = synchronized(values) {
            values.find { caseMappingProvider().areEquivalent(it.model.name.value, name) }
        }

        operator fun plusAssign(value: Child): Unit = synchronized(values) {
            values.add(value)
        }

        operator fun minusAssign(name: String): Unit = synchronized(values) {
            values.removeIf { caseMappingProvider().areEquivalent(it.model.name.value, name) }
        }

        operator fun contains(name: String) = get(name) != null

        fun clear() = synchronized(values) {
            values.clear()
        }

        override fun iterator() = HashSet(values).iterator().iterator()
    }
}
