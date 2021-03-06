package com.dmdirc

import com.dmdirc.ClientSpec.Formatting.action
import com.dmdirc.ClientSpec.Formatting.channelEvent
import com.dmdirc.ClientSpec.Formatting.message
import com.dmdirc.ClientSpec.Formatting.notice
import com.dmdirc.ClientSpec.Formatting.serverEvent
import com.dmdirc.Style.CustomStyle
import com.dmdirc.edgar.Edgar.tr
import com.dmdirc.ktirc.events.IrcEvent
import com.dmdirc.ui.nicklist.NickListView
import com.uchuhimo.konf.Item
import javafx.application.HostServices
import javafx.beans.Observable
import javafx.beans.property.Property
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ListChangeListener
import javafx.geometry.Orientation.VERTICAL
import javafx.scene.control.ScrollBar
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.util.Callback
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fxmisc.flowless.VirtualizedScrollPane
import java.time.format.DateTimeFormatter

enum class WindowType {
    ROOT, SERVER, CHANNEL
}

class WindowModel(
    initialName: String,
    val type: WindowType,
    val connection: ConnectionContract.Controller?,
    private val eventMapper: IrcEventMapper,
    private val config: ClientConfig,
    connectionId: String?
) {
    val name: Property<String> = SimpleStringProperty(initialName).threadAsserting()
    val title: Property<String> = SimpleStringProperty(initialName).threadAsserting()
    val unreadStatus: Property<MessageFlag?> = SimpleObjectProperty<MessageFlag?>(null).threadAsserting()
    val isConnection = type == WindowType.SERVER
    val sortKey = "${connectionId ?: ""} ${if (isConnection) "" else initialName.toLowerCase()}"
    val lines = mutableListOf<Array<StyledSpan>>().observable()
    val inputField: Property<String> = SimpleStringProperty("").threadAsserting()

    companion object {
        fun extractor(): Callback<WindowModel, Array<Observable>> {
            return Callback { m ->
                arrayOf(
                    m.title, m.unreadStatus, m?.connection?.connected ?: SimpleBooleanProperty(false)
                )
            }
        }
    }

    fun getTitle(): String {
        val connectionName = if (connection?.networkName?.isBlank() != false) {
            name.value
        } else {
            connection.networkName
        }
        return when {
            isConnection -> tr("DMDirc: %s").format(connectionName)
            else -> tr("DMDirc: %s | %s").format(name.value, connectionName)
        }
    }

    fun handleInput() {
        val texts = inputField.value.split("\n")
        texts.forEach { text ->
            if (text.isNotEmpty()) {
                when {
                    text.startsWith("/me ") -> connection?.sendAction(name.value, text.substring(4))
                    text.trim() == "/away" -> connection?.sendAway()
                    text.startsWith("/away ") -> connection?.sendAway(text.substring(6))
                    else -> connection?.sendMessage(name.value, text)
                }
            }
        }
    }

    fun handleEvent(event: IrcEvent) {
        eventMapper.displayableText(event)?.let {
            addLine(event.timestamp, eventMapper.flags(event), it)
        }
    }

    fun addLine(timestamp: String, flags: Set<MessageFlag>, args: Array<String>) {
        val message = " ${config[MessageFlag.formatter(flags)].format(*args)}"
        val spans = message.detectLinks().convertControlCodes().toMutableList()
        spans.add(0, StyledSpan(timestamp, setOf(Style.CustomStyle("timestamp"))))

        val unreadFlags = unreadStatus.value?.let { flags + it } ?: flags
        unreadStatus.value = unreadFlags.maxBy { it.ordinal }

        val flagStyles = flags.map { CustomStyle("messagetype-${it.name}") }
        lines.add(spans.map { StyledSpan(it.content, it.styles + flagStyles) }.toTypedArray())

        if (MessageFlag.Highlight in flags) {
            connection?.notify(this, message)
        }
    }

    private val IrcEvent.timestamp: String
        get() = metadata.time.format(DateTimeFormatter.ofPattern(config[ClientSpec.Formatting.timestamp]))
}

enum class MessageFlag {
    Away, ServerEvent, ChannelEvent, Self, Message, Action, Notice, Highlight;

    companion object {
        fun formatter(flags: Set<MessageFlag>): Item<String> = when {
            Message in flags -> message
            Action in flags -> action
            Notice in flags -> notice
            ChannelEvent in flags -> channelEvent
            else -> serverEvent
        }
    }
}

class WindowUI(
    model: WindowModel,
    hostServices: HostServices,
    nickListView: NickListView,
    imageLoader: (String) -> ImageLoader
) : AnchorPane() {

    private var scrollbar: ScrollBar? = null
    private val textArea = IrcTextArea({ url -> hostServices.showDocument(url) }, { url -> imageLoader(url) })
    val inputField = MagicInput(model.inputField, model)
    private var autoScroll = true

    init {
        val borderPane = BorderPane().apply {
            center = VirtualizedScrollPane(textArea.apply {
                isFocusTraversable = false
            }).apply {
                scrollbar = childrenUnmodifiable.filterIsInstance<ScrollBar>().find {
                    it.orientation == VERTICAL
                }
            }
            if (!model.isConnection) {
                right = nickListView
            }
            bottom = inputField
            AnchorPane.setTopAnchor(this, 0.0)
            AnchorPane.setLeftAnchor(this, 0.0)
            AnchorPane.setRightAnchor(this, 0.0)
            AnchorPane.setBottomAnchor(this, 0.0)
        }
        children.add(borderPane)
        textArea.totalHeightEstimateProperty().addListener { _, _, _ ->
            if (autoScroll) {
                scrollbar?.valueProperty()?.value = scrollbar?.max
            }
        }
        scrollbar?.addEventFilter(MouseEvent.MOUSE_PRESSED) {
            runLater {
                autoScroll = scrollbar?.valueProperty()?.value == scrollbar?.max
            }
        }
        scrollbar?.addEventFilter(MouseEvent.MOUSE_RELEASED) {
            runLater {
                autoScroll = scrollbar?.valueProperty()?.value == scrollbar?.max
            }
        }
        textArea.addEventFilter(ScrollEvent.SCROLL) {
            GlobalScope.launch {
                delay(100)
                runLater {
                    autoScroll = scrollbar?.valueProperty()?.value == scrollbar?.max
                }
            }
        }
        model.lines.addListener(ListChangeListener { change ->
            // TODO: Support ops other than just appending lines (editing, deleting, inserting earlier, etc).
            while (change.next()) {
                if (change.wasAdded()) {
                    change.addedSubList.forEach { line ->
                        textArea.appendText("\n")
                        line.forEach { segment ->
                            val position = textArea.length
                            textArea.appendText(segment.content)
                            textArea.setStyle(position, textArea.length, segment.styles)
                        }
                    }
                }
            }
        })
    }
}

class MagicInput(private val modelText: Property<String>, model: WindowModel) : VBox() {
    private var active: TextInputControl? = null
    private val single = TextField().apply {
        styleClass.add("input-field")
    }
    private val multi = TextArea().apply {
        styleClass.add("input-field")
        isWrapText = true
        prefRowCount = 12
    }
    init {
        single.textProperty().bindBidirectional(modelText)
        multi.textProperty().bindBidirectional(modelText)
        active = single
        children.add(single)
        addEventFilter(KeyEvent.KEY_RELEASED) {
            if (active == single && it.isShiftDown && it.code == KeyCode.ENTER) {
                swap()
            }
            if (active == multi && !modelText.value.contains("\n")) {
                swap()
                runLater {
                    active?.end()
                }
            }
            if (it.isShiftDown && !it.isControlDown && it.code == KeyCode.ENTER) {
                modelText.value += "\n"
                runLater {
                    active?.end()
                }
            } else if (!it.isShiftDown && !it.isControlDown && it.code == KeyCode.ENTER) {
                model.handleInput()
                modelText.value = ""
                if (active == multi) {
                    swap()
                }
            }
        }
    }

    private fun swap() {
        runLater {
            val focused = active?.focusedProperty()?.value ?: false
            if (active == single) {
                single.textProperty().unbindBidirectional(modelText)
                multi.textProperty().bindBidirectional(modelText)
                children.remove(single)
                children.add(multi)
                active = multi
            } else {
                multi.textProperty().unbindBidirectional(modelText)
                single.textProperty().bindBidirectional(modelText)
                children.remove(multi)
                children.add(single)
                active = single
            }
            if (focused) {
                active?.requestFocus()
            }
        }
    }

    override fun requestFocus() {
        active?.requestFocus()
    }
}
