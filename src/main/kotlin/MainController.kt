package com.dmdirc

import javafx.beans.property.SimpleObjectProperty
import javafx.collections.ObservableList
import org.kodein.di.generic.instance
import tornadofx.Controller
import tornadofx.observable

class MainController : Controller() {

    private val config1 by kodein.instance<ClientConfig>()
    val windows: ObservableList<Window> = emptyList<Window>().toMutableList().observable()
    val selectedWindow: SimpleObjectProperty<Window> = SimpleObjectProperty()

    init {
        autoConnect()
    }

    private fun autoConnect() {
        config1[ClientSpec.servers].filter { it.autoconnect }.forEach(this::connect)
    }

    fun connect(connectionDetails: ConnectionDetails) {
        Connection(connectionDetails.hostname,  connectionDetails.port, connectionDetails.password, connectionDetails.tls, config1, this).connect()
    }

    fun joinChannel(value: String) {
        selectedWindow.value.connection?.joinChannel(value) ?: return
    }

}