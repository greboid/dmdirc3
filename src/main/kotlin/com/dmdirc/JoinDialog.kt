package com.dmdirc

import com.jukusoft.i18n.I
import com.jukusoft.i18n.I.tr
import javafx.beans.property.*
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.*
import javafx.scene.layout.BorderWidths
import javafx.scene.layout.CornerRadii
import javafx.scene.paint.Color



object JoinDialogContract {
    interface Controller {
        fun join(channel: String)
    }

    interface ViewModel : ValidatingModel {
        val open: BooleanProperty
        val channel: StringProperty
        fun onTextAction()
        fun onJoinPressed()
        fun onCancelPressed()
    }
}

class JoinDialogController(private val controller: MainContract.Controller) : JoinDialogContract.Controller {
    override fun join(channel: String) {
        controller.joinChannel(channel)
    }
}

class JoinDialogModel(private val controller: JoinDialogContract.Controller) : JoinDialogContract.ViewModel {

    override val open = SimpleBooleanProperty(true)
    override val channel = SimpleStringProperty()
    override val valid = ValidatorChain()

    private fun commit() {
        if (!valid.value) {
            return
        }
        controller.join(channel.value)
        close()
    }

    private fun close() = open.set(false)

    override fun onTextAction() = commit()
    override fun onJoinPressed() = commit()
    override fun onCancelPressed() = close()

}

class JoinDialog(model: JoinDialogContract.ViewModel, private val parent: ObjectProperty<Node>) : VBox() {
    fun show() {
        parent.value = this
    }

    init {
        model.open.addListener { _, _, newValue ->
            if (newValue == false) {
                parent.value = null
            }
        }
        styleClass.add("join-dialog")
        children.addAll(
            VBox().apply {
                styleClass.add("dialog-background")
                children.addAll(
                    Label(tr("Enter channel to join: ")),
                    TextField().apply {
                        bindRequiredTextControl(this, model.channel, model)
                        setOnAction { model.onTextAction() }
                    },
                    ButtonBar().apply {
                        buttons.addAll(
                            Button(I.tr("Join")).apply {
                                ButtonBar.setButtonData(this, ButtonBar.ButtonData.OK_DONE)
                                disableProperty().bind(model.valid.not())
                                setOnAction { model.onJoinPressed() }
                            },
                            Button(I.tr("Cancel")).apply {
                                ButtonBar.setButtonData(this, ButtonBar.ButtonData.CANCEL_CLOSE)
                                setOnAction { model.onCancelPressed() }
                            }
                        )
                    }
                )
            },
            VBox().apply {
                VBox.setVgrow(this, Priority.ALWAYS)
            }
        )
    }
}
