package org.janelia.saalfeldlab.paintera.state;

import bdv.fx.viewer.ViewerPanelFX;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import javafx.concurrent.Task;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import net.imglib2.type.label.Label;
import net.imglib2.type.numeric.IntegerType;
import org.janelia.saalfeldlab.fx.Tasks;
import org.janelia.saalfeldlab.fx.actions.ActionSet;
import org.janelia.saalfeldlab.fx.actions.NamedKeyCombination;
import org.janelia.saalfeldlab.fx.actions.PainteraActionSet;
import org.janelia.saalfeldlab.fx.event.KeyTracker;
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys;
import org.janelia.saalfeldlab.paintera.Paintera;
import org.janelia.saalfeldlab.paintera.control.IdSelector;
import org.janelia.saalfeldlab.paintera.control.actions.LabelActionType;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignment;
import org.janelia.saalfeldlab.paintera.control.lock.LockedSegments;
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

import static javafx.scene.input.KeyEvent.KEY_PRESSED;

public class LabelSourceStateIdSelectorHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final LongPredicate FOREGROUND_CHECK = Label::isForeground;

  private static final ExecutorService selectorService = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("id selector thread").build());

  private final DataSource<? extends IntegerType<?>, ?> source;

  private final IdService idService;

  private final SelectedIds selectedIds;

  private final FragmentSegmentAssignment assignment;

  private final LockedSegments lockedSegments;

  private final Runnable refreshMeshes;

  private Task<?> selectAllTask;

  private Future<?> selectAllFuture;

  /**
   * @param source         that contains the labels to select
   * @param idService      provides the next ID to select
   * @param selectedIds    store the selected IDs
   * @param assignment     used to lock fragments and segments
   * @param lockedSegments track the locked segments
   * @param refreshMeshes  after canceling a selection task
   */
  public LabelSourceStateIdSelectorHandler(
		  final DataSource<? extends IntegerType<?>, ?> source,
		  final IdService idService,
		  final SelectedIds selectedIds,
		  final FragmentSegmentAssignment assignment,
		  final LockedSegments lockedSegments,
		  final Runnable refreshMeshes) {

	this.source = source;
	this.idService = idService;
	this.selectedIds = selectedIds;
	this.assignment = assignment;
	this.lockedSegments = lockedSegments;
	this.refreshMeshes = refreshMeshes;
  }

  public List<ActionSet> makeActionSets(NamedKeyCombination.CombinationMap keyBindings, KeyTracker keyTracker, Supplier<ViewerPanelFX> getActiveViewer) {

	final IdSelector selector = new IdSelector(source, selectedIds, getActiveViewer, FOREGROUND_CHECK);

	final var toggleLabelActions = new PainteraActionSet("toggle single id", LabelActionType.Toggle, actionSet -> {
	  final var selectMaxCount = selector.selectFragmentWithMaximumCountAction();
	  selectMaxCount.verify(event -> keyTracker.areOnlyTheseKeysDown(KeyCode.ALT) || keyTracker.noKeysActive());
	  selectMaxCount.verify(mouseEvent -> !Paintera.getPaintera().getMouseTracker().isDragging());
	  selectMaxCount.verifyButtonTrigger(MouseButton.PRIMARY);
	  actionSet.addAction(selectMaxCount);
	});

	final var appendLabelActions = new PainteraActionSet("append id", LabelActionType.Append, actionSet -> {
	  final var appendMaxCount = selector.appendFragmentWithMaximumCountAction();
	  appendMaxCount.verify(mouseEvent -> !Paintera.getPaintera().getMouseTracker().isDragging());
	  appendMaxCount.verify(mouseEvent -> {
		final var button = mouseEvent.getButton();
		final var leftClickTrigger = button == MouseButton.PRIMARY && keyTracker.areOnlyTheseKeysDown(KeyCode.CONTROL);
		final var rightClickTrigger = button == MouseButton.SECONDARY && keyTracker.noKeysActive();
		return leftClickTrigger || rightClickTrigger;
	  });
	  actionSet.addAction(appendMaxCount);
	});

	final var selectAllActions = new PainteraActionSet("Select All", LabelActionType.SelectAll, actionSet -> {
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.keyMatchesBinding(keyBindings, LabelSourceStateKeys.SELECT_ALL);
		keyAction.verify(event -> selectAllTask == null);
		keyAction.onAction(keyEvent -> {
		  final var selectTask = Tasks.createTask(task -> {
					Paintera.getPaintera().getBaseView().getPane().getScene().setCursor(Cursor.WAIT);
					selectAllTask = task;
					selector.selectAll();
				  })
				  .onEnd(task -> {
					selectAllTask = null;
					Paintera.getPaintera().getBaseView().getPane().getScene().setCursor(Cursor.DEFAULT);
				  });
		  selectAllFuture = selectorService.submit(selectTask);
		});
	  });
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.keyMatchesBinding(keyBindings, LabelSourceStateKeys.SELECT_ALL_IN_CURRENT_VIEW);
		keyAction.verify(event -> selectAllTask == null);
		keyAction.verify(event -> getActiveViewer.get() != null);
		keyAction.onAction(keyEvent -> {
		  final var selectTask = Tasks.createTask(task -> {
					Paintera.getPaintera().getBaseView().getPane().getScene().setCursor(Cursor.WAIT);
					selectAllTask = task;
					selector.selectAllInCurrentView(getActiveViewer.get());
				  })
				  .onEnd(objectUtilityTask -> {
					selectAllTask = null;
					Paintera.getPaintera().getBaseView().getPane().getScene().setCursor(Cursor.DEFAULT);
				  });
		  selectAllFuture = selectorService.submit(selectTask);
		});
	  });
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.setName("Cancel Select All");
		keyAction.keysDown(KeyCode.ESCAPE);
		keyAction.verify(keyEvent -> selectAllTask != null);
		keyAction.onAction(keyEvent -> {
		  selectAllTask.cancel();
		  selectAllFuture.cancel(true);
		  refreshMeshes.run();
		  selectedIds.deactivateAll();
		});
	  });
	});
	final var lockSegmentActions = new PainteraActionSet("Toggle Segment Lock", LabelActionType.Lock, actionSet -> {
	  actionSet.addKeyAction(KEY_PRESSED, keyAction -> {
		keyAction.keyMatchesBinding(keyBindings, LabelSourceStateKeys.LOCK_SEGEMENT);
		keyAction.onAction(keyEvent -> selector.toggleLock(assignment, lockedSegments));
	  });
	});

	return List.of(toggleLabelActions, appendLabelActions, selectAllActions, lockSegmentActions);
  }

  public long nextId() {

	return nextId(true);
  }

  public long nextId(boolean activate) {

	final long next = idService.next();
	if (activate) {
	  selectedIds.activate(next);
	}
	return next;
  }
}
