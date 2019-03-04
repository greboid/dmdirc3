package com.dmdirc

import com.jukusoft.i18n.I.tr
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.StringProperty
import javafx.collections.transformation.SortedList
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.Callback
import javafx.util.StringConverter

class NodeListCellFactory(private val list: ListView<WindowModel>) : Callback<ListView<WindowModel>, ListCell<WindowModel>> {
    override fun call(param: ListView<WindowModel>?): ListCell<WindowModel> {
        return NodeListCell(list)
    }
}

class NodeListCell(list: ListView<WindowModel>) : ListCell<WindowModel>() {
    init {
        prefWidthProperty().bind(list.widthProperty())
        maxWidth = Control.USE_PREF_SIZE
    }
    override fun updateItem(node: WindowModel?, empty: Boolean) {
        super.updateItem(node, empty)
        if (node != null && !empty) {
            graphic = BorderPane().apply {
                styleClass.add("node-${node.type.name.toLowerCase()}")
                if (node.hasUnreadMessages.value) {
                    styleClass.add("node-unread")
                }
                if (node.type == WindowType.SERVER) {
                    right = Label().apply {
                        styleClass.add("node-cog}")
                        graphic = FontAwesomeIconView(FontAwesomeIcon.COG)
                        contextMenu = ContextMenu().apply {
                            items.add(MenuItem(tr("Placeholder")))
                        }
                        //TODO: This needs to work cross platform as expected
                        onMouseClicked = EventHandler {
                            if (it.button == MouseButton.PRIMARY) {
                                contextMenu.show(graphic, it.screenX, it.screenY)
                            }
                        }

                    }
                }
                left = Label(node.title.value)
            }
            tooltip = Tooltip(node.title.value)
        }
        if (empty) {
            graphic = null
        }
    }
}

class MainView(
    private val controller: MainContract.Controller,
    val config: ClientConfig,
    val joinDialogProvider: () -> JoinDialog,
    val settingsDialogProvider: () -> SettingsDialog,
    private val primaryStage: Stage,
    titleProperty: StringProperty
) : BorderPane() {
    private val selectedWindow = SimpleObjectProperty<Node>()

    init {
        top = MenuBar().apply {
            menus.addAll(
                Menu(tr("File")).apply {
                    items.add(
                        MenuItem(tr("Quit")).apply {
                            setOnAction {
                                primaryStage.close()
                            }
                        }
                    )
                },
                Menu(tr("IRC")).apply {
                    items.addAll(
                        MenuItem(tr("Server List")).apply {
                            setOnAction {
                                ServerListController(controller, primaryStage, config).create()
                            }
                        },
                        MenuItem(tr("Join Channel")).apply {
                            setOnAction {
                                joinDialogProvider().show()
                            }
                            disableProperty().bind(controller.selectedWindow.isNull)
                        }
                    )
                },
                Menu(tr("Settings")).apply {
                    items.add(
                        MenuItem(tr("Settings")).apply {
                            setOnAction {
                                settingsDialogProvider().show()
                            }
                        }
                    )
                }
            )
        }
        left = ListView(SortedList(controller.windows, compareBy { it.sortKey })).apply {
            styleClass.add("tree-view")
            selectionModel.selectedItemProperty().addListener { _, _, newValue ->
                controller.selectedWindow.value = newValue
            }
            cellFactory = NodeListCellFactory(this)
            contextMenu = ContextMenu().apply {
                items.addAll(
                    MenuItem(tr("Close")).apply {
                        setOnAction {
                            if (controller.selectedWindow.value.isConnection) {
                                controller.selectedWindow.value.connection?.disconnect()
                            } else {
                                controller.leaveChannel(controller.selectedWindow.value.name.value)
                            }
                        }
                    }
                )
            }
        }
        centerProperty().bindBidirectional(selectedWindow)
        primaryStage.icons.add(Image(MainView::class.java.getResourceAsStream("/logo.png")))
        titleProperty.bindBidirectional(controller.selectedWindow, TitleStringConverter())
        controller.selectedWindow.addListener { _, _, newValue ->
            selectedWindow.value = newValue?.let {
                it.connection?.children?.get(it.name.value)?.ui
            } ?: VBox()
        }
    }
}

class TitleStringConverter : StringConverter<WindowModel>() {

    override fun fromString(string: String?) = TODO("not implemented")

    override fun toString(window: WindowModel?): String = when {
        window == null -> tr("DMDirc")
        window.isConnection -> tr("DMDirc: %s").format(window.connection?.networkName ?: "")
        else -> tr("DMDirc: %s | %s").format(window.name.value, window.connection?.networkName ?: "")
    }

}
