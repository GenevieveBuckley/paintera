package org.janelia.saalfeldlab.paintera.control.modes

import javafx.beans.value.ChangeListener
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.event.EventHandler
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.input.KeyEvent.KEY_RELEASED
import net.imglib2.type.numeric.IntegerType
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.ActionSet.Companion.installActionSet
import org.janelia.saalfeldlab.fx.actions.ActionSet.Companion.removeActionSet
import org.janelia.saalfeldlab.fx.actions.PainteraActionSet
import org.janelia.saalfeldlab.fx.extensions.createValueBinding
import org.janelia.saalfeldlab.fx.extensions.nullableVal
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.ENTER_SHAPE_INTERPOLATION_MODE
import org.janelia.saalfeldlab.paintera.control.ShapeInterpolationController
import org.janelia.saalfeldlab.paintera.control.actions.AllowedActions
import org.janelia.saalfeldlab.paintera.control.actions.LabelActionType
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.tools.Tool
import org.janelia.saalfeldlab.paintera.control.tools.paint.*
import org.janelia.saalfeldlab.paintera.control.tools.paint.PaintTool.Companion.createPaintStateContext
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.LabelSourceState
import org.janelia.saalfeldlab.paintera.state.SourceState
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts


object PaintLabelMode : AbstractToolMode() {

    val activeSourceToSourceStateContextBinding = activeSourceStateProperty.createValueBinding { binding -> createPaintStateContext(binding) }
    val statePaintContext by activeSourceToSourceStateContextBinding.nullableVal()

    private val paintBrushTool = PaintBrushTool(activeSourceStateProperty)
    private val fill2DTool = Fill2DTool(activeSourceStateProperty)
    private val fill3DTool = Fill3DTool(activeSourceStateProperty)
    private val restrictTool = RestrictPaintToLabelTool(activeSourceStateProperty)

    override val toolBarTools: ObservableList<Tool> by lazy {
        FXCollections.observableArrayList(
            NavigationTool,
            paintBrushTool,
            fill2DTool,
            fill3DTool,
            restrictTool
        )
    }

    override val modeActions: List<ActionSet> by lazy {
        listOf(
            *getToolTriggerActions().toTypedArray(),
            getSelectNextIdAction(),
            getResetMaskAction(),
        )
    }

    override val allowedActions = AllowedActions.PAINT

    private val moveModeActionsToActiveViewer = ChangeListener<OrthogonalViews.ViewerAndTransforms?> { _, old, new ->
        /* remove the mode actions from the deactivated viewer, add to the activated viewer */
        modeActions.forEach { actionSet ->
            old?.viewer()?.removeActionSet(actionSet)
            new?.viewer()?.installActionSet(actionSet)
        }

        /* set the currently activeTool for this viewer, or if no viewer active, switch to no tool  */
        new?.let { switchTool(activeTool ?: NavigationTool) } ?: switchTool(null)
    }

    override fun enter() {
        activeViewerProperty.addListener(moveModeActionsToActiveViewer)
        super.enter()
    }

    override fun exit() {
        activeViewerProperty.removeListener(moveModeActionsToActiveViewer)
        activeViewerProperty.get()?.let {
            modeActions.forEach { actionSet ->
                it.viewer()?.removeActionSet(actionSet)
            }
        }
        super.exit()
    }


    val togglePaintBrush = PainteraActionSet("toggle paint tool", PaintActionType.Paint) {
        KEY_PRESSED(KeyCode.SPACE) {
            name = "Enter Paint Mode"
            keysExclusive = false
            consume = false
            verify { activeSourceStateProperty.get()?.dataSource is MaskedSource<*, *> }
            verify { activeTool !is PaintBrushTool }
            onAction { switchTool(paintBrushTool) }
        }
        KEY_PRESSED(KeyCode.SPACE) {
            name = "Supress Paint Mode Key Down"
            /* swallow SPACE down events while painting*/
            filter = true
            consume = true
            verify { activeTool is PaintBrushTool }
        }
        KEY_RELEASED {
            name = "Exit Paint Mode"
            triggerIfDisabled = true
            keysReleased(KeyCode.SPACE)
            verify { activeTool is PaintBrushTool }
            onAction { switchTool(NavigationTool) }
        }
    }

    val toggleFill2D = PainteraActionSet("toggle fill 2D overlay", PaintActionType.Fill) {
        KEY_PRESSED(KeyCode.F) {
            verify { activeTool !is Fill2DTool }
            onAction { switchTool(fill2DTool) }
        }

        KEY_PRESSED(KeyCode.F) {
            /* swallow F down events while Filling*/
            filter = true
            consume = true
            verify { activeTool is Fill2DTool }
        }

        KEY_RELEASED {
            triggerIfDisabled = true
            keysReleased(KeyCode.F)
            verify { activeTool is Fill2DTool }
            onAction { switchTool(NavigationTool) }
        }
    }

    val toggleFill3D = PainteraActionSet("toggle fill 3D overlay", PaintActionType.Fill) {
        KEY_PRESSED(KeyCode.F, KeyCode.SHIFT) {
            verify { activeTool !is Fill3DTool }
            onAction { switchTool(fill3DTool) }
        }
        KEY_PRESSED {
            /* swallow F down events while filling*/
            filter = true
            consume = true
            verify { it.code in listOf(KeyCode.F, KeyCode.SHIFT) && activeTool is Fill3DTool }
        }

        KEY_RELEASED {
            triggerIfDisabled = true
            keysReleased(KeyCode.F, KeyCode.SHIFT)
            verify { activeTool is Fill3DTool }
            onAction {
                when (it.code) {
                    KeyCode.F -> switchTool(NavigationTool)
                    KeyCode.SHIFT -> switchTool(fill2DTool)
                    else -> return@onAction
                }
            }
        }
    }

    val restrictPaintToLabel = PainteraActionSet("toggle restrict paint", PaintActionType.Restrict) {
        KEY_PRESSED(KeyCode.SHIFT, KeyCode.R) {
            verify { activeTool !is RestrictPaintToLabelTool }
            onAction { switchTool(restrictTool) }
        }
        KEY_PRESSED {
            /* swallow F down events while Filling*/
            filter = true
            consume = true
            verify { it.code in listOf(KeyCode.R, KeyCode.SHIFT) && activeTool is Fill3DTool }
        }

        KEY_RELEASED {
            keysReleased(KeyCode.SHIFT, KeyCode.R)
            verify { activeTool is RestrictPaintToLabelTool }
            onAction { switchTool(NavigationTool) }
        }
    }

    val enterShapeInterpolationMode = PainteraActionSet(ENTER_SHAPE_INTERPOLATION_MODE, PaintActionType.ShapeInterpolation) {
        KEY_PRESSED(KeyCode.S) {
            verify {
                when (activeSourceStateProperty.get()) {
                    is LabelSourceState<*, *> -> true
                    is ConnectomicsLabelState<*, *> -> true
                    else -> false
                }
            }
            verify { activeSourceStateProperty.get()?.dataSource as? MaskedSource<out IntegerType<*>, *> != null }
            onAction {
                newShapeInterpolationModeForSource(activeSourceStateProperty.get())?.let {
                    paintera.baseView.changeMode(it)
                }
            }
        }
    }


    private fun getToolTriggerActions() = listOf(
        togglePaintBrush,
        toggleFill2D,
        toggleFill3D,
        restrictPaintToLabel,
        enterShapeInterpolationMode
    )

    private fun getSelectNextIdAction() = PainteraActionSet("Create New Segment", LabelActionType.CreateNew) {
        KEY_PRESSED(keyBindings!!, LabelSourceStateKeys.NEXT_ID) {
            verify { activeTool !is PaintTool }
            onAction {
                statePaintContext?.nextId(activate = true)
            }
        }
    }

    private fun getResetMaskAction() = PainteraActionSet("Force Mask Reset", PaintActionType.Paint) {
        KEY_PRESSED(KeyCode.SHIFT, KeyCode.ESCAPE) {
            verify {
                activeSourceStateProperty.get()?.let { state ->
                    (state.dataSource as? MaskedSource<*, *>)?.let { maskedSource ->
                        maskedSource.currentMask?.let { true } ?: false
                    } ?: false
                } ?: false
            }
            onAction {
                InvokeOnJavaFXApplicationThread {
                    PainteraAlerts.confirmation("Yes", "No", false, paintera.pane.scene.window).apply {
                        headerText = "Force Reset the Active Mask?"
                        contentText = """
                            This may result in loss of some of the most recent uncommitted label annotations. This usually is only necessary if the mask is stuck on "busy".

                            Only do this if you suspect and error has occured. You may consider waiting a bit to see if the mask releases on it's own.
                        """.trimIndent()
                        val okButton = dialogPane.lookupButton(ButtonType.OK) as Button
                        okButton.onAction = EventHandler {
                            activeSourceStateProperty.get()?.let { state ->
                                (state.dataSource as? MaskedSource<*, *>)?.resetMasks()
                            }
                        }
                        showAndWait()
                    }
                }
            }
        }
    }

    private fun newShapeInterpolationModeForSource(sourceState: SourceState<*, *>?): ShapeInterpolationMode<*>? {
        return sourceState?.let { state ->
            (state.dataSource as? MaskedSource<out IntegerType<*>, *>)?.let { maskedSource ->
                when (state) {
                    is ConnectomicsLabelState<*, *> -> {
                        with(state) {
                            ShapeInterpolationController(
                                maskedSource,
                                ::refreshMeshes,
                                selectedIds,
                                idService,
                                converter(),
                                fragmentSegmentAssignment,
                            )
                        }
                    }
                    is LabelSourceState<*, *> -> {
                        with(state) {
                            ShapeInterpolationController(
                                maskedSource,
                                ::refreshMeshes,
                                selectedIds(),
                                idService(),
                                converter(),
                                assignment()
                            )
                        }
                    }
                    else -> null
                }?.let {
                    ShapeInterpolationMode(it, this)
                }
            }
        }
    }

}


