package org.janelia.saalfeldlab.paintera.meshes;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import eu.mihosoft.jcsg.ext.openjfx.shape3d.PolygonMeshView;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.*;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.util.concurrent.HashPriorityQueueBasedTaskExecutor;
import org.janelia.saalfeldlab.util.grids.Grids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * @author Philipp Hanslovsky
 * @author Igor Pisarev
 */
public class MeshGeneratorJobManager<T>
{
	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final class SceneUpdateJobParameters
	{
		final BlockTree<BlockTreeFlatKey, BlockTreeNode<BlockTreeFlatKey>> sceneBlockTree;
		final CellGrid[] rendererGrids;
		final int simplificationIterations;
		final double smoothingLambda;
		final int smoothingIterations;
		final double minLabelRatio;

		SceneUpdateJobParameters(
			final BlockTree<BlockTreeFlatKey, BlockTreeNode<BlockTreeFlatKey>> sceneBlockTree,
			final CellGrid[] rendererGrids,
			final int simplificationIterations,
			final double smoothingLambda,
			final int smoothingIterations,
			final double minLabelRatio)
		{
			this.sceneBlockTree = sceneBlockTree;
			this.rendererGrids = rendererGrids;
			this.simplificationIterations = simplificationIterations;
			this.smoothingLambda = smoothingLambda;
			this.smoothingIterations = smoothingIterations;
			this.minLabelRatio = minLabelRatio;
		}
	}

	private enum TaskState
	{
		CREATED,
		SCHEDULED,
		RUNNING,
		COMPLETED,
		INTERRUPTED
	}

	private class Task
	{
		final Runnable task;
		MeshWorkerPriority priority;
		final long tag;
		TaskState state = TaskState.CREATED;

		Task(final Runnable task, final MeshWorkerPriority priority, final long tag)
		{
			this.task = task;
			this.priority = priority;
			this.tag = tag;
		}
	}

	private enum BlockTreeNodeState
	{
		/**
		 * Mesh for the block is displayed normally.
		 */
		VISIBLE,

		/**
		 * Mesh for the block has been generated, but has not been added onto the scene yet.
		 */
		RENDERED,

		/**
		 * Mesh for the block has been generated and added onto the scene, but is currently hidden
		 * because there are pending blocks with the same parent node that is currently visible.
		 *
		 * This state is used when increasing the resolution for a block that is currently visible:
		 *
		 *   --------------------
		 *  |       Visible      |
		 *   --------------------
		 *      |            |
		 *      |            |
		 *   --------   ---------
		 *  | Hidden | | Pending |
		 *   --------   ---------
		 *
		 * Once the pending block is rendered and added onto the scene, the parent block will be transitioned into the REMOVED state,
		 * and the higher-resolution blocks will be transitioned into the VISIBLE state.
		 */
		HIDDEN,

		/**
		 * Mesh for the block has been replaced by a set of higher-resolution blocks.
		 */
		REMOVED,

		/**
		 * Mesh for the blocks needs to be generated.
		 * This state is used for blocks that are already being generated and for those that are not yet started or scheduled.
		 */
		PENDING
	}

	private final class StatefulBlockTreeNode<K> extends BlockTreeNode<K>
	{
		BlockTreeNodeState state = BlockTreeNodeState.PENDING; // initial state is always PENDING

		StatefulBlockTreeNode(final K parentKey, final Set<K> children, final double distanceFromCamera)
		{
			super(parentKey, children, distanceFromCamera);
		}

		@Override
		public String toString()
		{
			return String.format("[state=%s, parentExists=%b, numChildren=%d, distanceFromCamera=%.5f]", state, parentKey != null, children.size(), distanceFromCamera);
		}
	}


	private final DataSource<?, ?> source;

	private final T identifier;

	private final AffineTransform3D[] unshiftedWorldTransforms;

	private final Map<ShapeKey<T>, Task> tasks = new HashMap<>();

	private final ObservableMap<ShapeKey<T>, Pair<MeshView, Node>> meshesAndBlocks;

	private final Pair<Group, Group> meshesAndBlocksGroups;

	private final MeshViewUpdateQueue<T> meshViewUpdateQueue;

	private final InterruptibleFunction<T, Interval[]>[] getBlockLists;

	private final InterruptibleFunction<ShapeKey<T>, Pair<float[], float[]>>[] getMeshes;

	private final ExecutorService managers;

	private final HashPriorityQueueBasedTaskExecutor<MeshWorkerPriority> workers;

	private final int numScaleLevels;

	private final IndividualMeshProgress meshProgress;

	private final AtomicBoolean isInterrupted = new AtomicBoolean();

	private final ObjectProperty<SceneUpdateJobParameters> sceneJobUpdateParametersProperty = new SimpleObjectProperty<>();

	/**
	 * Block tree representing the current state of the scene and all necessary pending blocks necessary for transforming it into the requested tree.
	 * When all tasks are finished, it is expected to be identical to the requested tree.
	 */
	private final BlockTree<ShapeKey<T>, StatefulBlockTreeNode<ShapeKey<T>>> blockTree = new BlockTree<>();

	/**
	 * Block tree representing the expected state of the scene.
	 */
	private BlockTree<ShapeKey<T>, BlockTreeNode<ShapeKey<T>>> requestedBlockTree = new BlockTree<>();

	private final AtomicLong sceneUpdateCounter = new AtomicLong();

	public MeshGeneratorJobManager(
			final DataSource<?, ?> source,
			final T identifier,
			final ObservableMap<ShapeKey<T>, Pair<MeshView, Node>> meshesAndBlocks,
			final Pair<Group, Group> meshesAndBlocksGroups,
			final MeshViewUpdateQueue<T> meshViewUpdateQueue,
			final InterruptibleFunction<T, Interval[]>[] getBlockLists,
			final InterruptibleFunction<ShapeKey<T>, Pair<float[], float[]>>[] getMeshes,
			final AffineTransform3D[] unshiftedWorldTransforms,
			final ExecutorService managers,
			final HashPriorityQueueBasedTaskExecutor<MeshWorkerPriority> workers,
			final IndividualMeshProgress meshProgress)
	{
		this.source = source;
		this.identifier = identifier;
		this.meshesAndBlocks = meshesAndBlocks;
		this.meshesAndBlocksGroups = meshesAndBlocksGroups;
		this.meshViewUpdateQueue = meshViewUpdateQueue;
		this.getBlockLists = getBlockLists;
		this.getMeshes = getMeshes;
		this.unshiftedWorldTransforms = unshiftedWorldTransforms;
		this.managers = managers;
		this.workers = workers;
		this.numScaleLevels = source.getNumMipmapLevels();
		this.meshesAndBlocks.addListener(this::handleMeshListChange);
		this.meshProgress = meshProgress;
	}

	public void submit(
			final BlockTree<BlockTreeFlatKey, BlockTreeNode<BlockTreeFlatKey>> sceneBlockTree,
			final CellGrid[] rendererGrids,
			final int simplificationIterations,
			final double smoothingLambda,
			final int smoothingIterations,
			final double minLabelRatio)
	{
		if (isInterrupted.get())
			return;

		final SceneUpdateJobParameters params = new SceneUpdateJobParameters(
				sceneBlockTree,
				rendererGrids,
				simplificationIterations,
				smoothingLambda,
				smoothingIterations,
				minLabelRatio
			);

		synchronized (sceneJobUpdateParametersProperty)
		{
			final boolean needToSubmit = sceneJobUpdateParametersProperty.get() == null;
			sceneJobUpdateParametersProperty.set(params);
			if (needToSubmit && !managers.isShutdown())
				managers.submit(withErrorPrinting(this::updateScene));
		}
	}

	public synchronized void interrupt()
	{
		if (isInterrupted.get())
			return;

		isInterrupted.set(true);
		meshesAndBlocks.clear();

		LOG.debug("Interrupting for {} keys={}", this.identifier, tasks.keySet());
		for (final InterruptibleFunction<T, Interval[]> getBlockList : this.getBlockLists)
			getBlockList.interruptFor(this.identifier);

		for (final InterruptibleFunction<ShapeKey<T>, Pair<float[], float[]>> getMesh : this.getMeshes)
			tasks.keySet().forEach(getMesh::interruptFor);
		interruptTasks(tasks.keySet());

		meshProgress.set(0, 0);
	}

	private synchronized void updateScene()
	{
		if (isInterrupted.get())
			return;

		LOG.debug("ID {}: scene update initiated", identifier);
		sceneUpdateCounter.incrementAndGet();

		final SceneUpdateJobParameters params;
		synchronized (sceneJobUpdateParametersProperty)
		{
			params = sceneJobUpdateParametersProperty.get();
			sceneJobUpdateParametersProperty.set(null);
		}

		// Update the block tree and get the set of blocks that still need to be rendered (and the total number of blocks in the new tree)
		final Pair<Set<ShapeKey<T>>, Integer> filteredBlocksAndNumTotalBlocks = updateBlockTree(params);

		// remove blocks from the scene that are not in the updated tree
		meshesAndBlocks.keySet().retainAll(blockTree.nodes.keySet());

		// stop tasks for blocks that are not in the updated tree
		final List<ShapeKey<T>> taskKeysToInterrupt = tasks.keySet().stream()
				.filter(key -> !blockTree.nodes.containsKey(key))
				.collect(Collectors.toList());
		interruptTasks(taskKeysToInterrupt);

		// re-prioritize all existing tasks with respect to the new distances between the blocks and the camera
		synchronized (workers)
		{
			final Map<Runnable, MeshWorkerPriority> reprioritizedTasks = new HashMap<>();
			for (final Entry<ShapeKey<T>, Task> entry : tasks.entrySet())
			{
				final ShapeKey<T> key = entry.getKey();
				final Task task = entry.getValue();
				if (task.state == TaskState.CREATED || task.state == TaskState.SCHEDULED)
				{
					assert blockTree.nodes.containsKey(key) : "Task for the pending block already exists but its new priority is missing: " + key;
					task.priority = new MeshWorkerPriority(blockTree.nodes.get(key).distanceFromCamera, key.scaleIndex());
					if (workers.containsTask(task.task))
					{
						assert task.state == TaskState.SCHEDULED : "Task is in the worker queue but its state is " + task.state + ", expected SCHEDULED: " + key;
						reprioritizedTasks.put(task.task, task.priority);
					}
				}
			}
			// check what if the task is already running and is not in the queue anymore
			if (!reprioritizedTasks.isEmpty())
				workers.addOrUpdateTasks(reprioritizedTasks);
		}

		// re-prioritize blocks in the FX mesh queue
		synchronized (meshViewUpdateQueue)
		{
			for (final Entry<ShapeKey<T>, StatefulBlockTreeNode<ShapeKey<T>>> entry : blockTree.nodes.entrySet())
			{
				final ShapeKey<T> key = entry.getKey();
				final StatefulBlockTreeNode<ShapeKey<T>> treeNode = entry.getValue();
				if (treeNode.state == BlockTreeNodeState.RENDERED && meshViewUpdateQueue.contains(key))
				{
					final MeshWorkerPriority newPriority = new MeshWorkerPriority(treeNode.distanceFromCamera, key.scaleIndex());
					meshViewUpdateQueue.updatePriority(key, newPriority);
				}
				else
				{
					assert !meshViewUpdateQueue.contains(key) : "Block that is in the " + treeNode.state + " state is not supposed to be in the FX queue: " + key;
				}
			}
		}

		// calculate how many tasks are already completed
		final int numTotalBlocksToRender = filteredBlocksAndNumTotalBlocks.getB();
		final int numActualBlocksToRender = filteredBlocksAndNumTotalBlocks.getA().size();
		final int numCompletedBlocks = numTotalBlocksToRender - numActualBlocksToRender - tasks.size();
		meshProgress.set(numTotalBlocksToRender, numCompletedBlocks);
		final int numExistingNonEmptyMeshes = (int) meshesAndBlocks.values().stream().filter(pair -> pair.getA() != null).count();
		LOG.debug("ID {}: numTasks={}, numCompletedTasks={}, numActualBlocksToRender={}. Number of meshes in the scene: {} ({} of them are non-empty)", identifier, numTotalBlocksToRender, numCompletedBlocks, numActualBlocksToRender, meshesAndBlocks.size(), numExistingNonEmptyMeshes);

		// create tasks for blocks that still need to be generated
		LOG.debug("Creating mesh generation tasks for {} blocks for id {}.", numActualBlocksToRender, identifier);
		filteredBlocksAndNumTotalBlocks.getA().forEach(this::createTask);

		// Update the meshes according to the new tree node states and submit top-level tasks
		final Queue<ShapeKey<T>> keyQueue = new ArrayDeque<>(blockTree.getRootKeys());
		final List<ShapeKey<T>> tasksToSubmit = new ArrayList<>();
		while (!keyQueue.isEmpty())
		{
			final ShapeKey<T> key = keyQueue.poll();
			final StatefulBlockTreeNode<ShapeKey<T>> treeNode = blockTree.nodes.get(key);
			keyQueue.addAll(treeNode.children);

			if (blockTree.isRoot(key) && treeNode.state == BlockTreeNodeState.PENDING)
			{
				// Top-level block
				tasksToSubmit.add(key);
			}
			else if (treeNode.state == BlockTreeNodeState.VISIBLE)
			{
				final boolean areAllHigherResBlocksReady = !treeNode.children.isEmpty() && treeNode.children.stream().allMatch(childKey -> blockTree.nodes.get(childKey).state == BlockTreeNodeState.HIDDEN);
				if (areAllHigherResBlocksReady)
				{
					// All children blocks in this block are ready, remove it and submit the tasks for next-level contained blocks if any
					treeNode.children.forEach(childKey -> {
						blockTree.nodes.get(childKey).state = BlockTreeNodeState.VISIBLE;
						final Pair<MeshView, Node> childMeshAndBlock = meshesAndBlocks.get(childKey);
						InvokeOnJavaFXApplicationThread.invoke(() -> setMeshVisibility(childMeshAndBlock, true));
					});

					treeNode.state = BlockTreeNodeState.REMOVED;
					assert !tasks.containsKey(key) : "Low-res parent block is being removed but there is a task for it: " + key;
					meshesAndBlocks.remove(key);

					treeNode.children.forEach(childKey -> tasksToSubmit.addAll(getPendingTasksForChildren(childKey)));
				}
				else
				{
					tasksToSubmit.addAll(getPendingTasksForChildren(key));
				}
			}
			else if (treeNode.state == BlockTreeNodeState.REMOVED)
			{
				tasksToSubmit.addAll(getPendingTasksForChildren(key));
			}
		}
		submitTasks(tasksToSubmit);

		// check that all blocks that are currently in the scene are backed by the entry in the tree and have a valid state
		assert meshesAndBlocks.keySet().stream().allMatch(blockTree.nodes::containsKey) : "Some of the blocks in the scene are not in the current tree";
		assert assertBlockTreeStates();
	}

	private synchronized void createTask(final ShapeKey<T> key)
	{
		final long tag = sceneUpdateCounter.get();
		final Runnable taskRunnable = () ->
		{
			final Task task;
			final BooleanSupplier isTaskCanceled;
			synchronized (this)
			{
				task = tasks.get(key);
				if (task == null || task.tag != tag)
				{
					LOG.debug("Task for key {} has been removed", key);
					return;
				}

				isTaskCanceled = () -> task.state == TaskState.INTERRUPTED || Thread.currentThread().isInterrupted();
				if (isTaskCanceled.getAsBoolean())
					return;

				assert task.state == TaskState.SCHEDULED : "Started to execute task but its state is " + task.state + " while it's supposed to be SCHEDULED: " + key;
				assert task.priority != null : "Started to execute task but its priority is null: " + key;

				task.state = TaskState.RUNNING;
				LOG.debug("Executing task for key {} at distance {}", key, task.priority.distanceFromCamera);
			}

			final Pair<float[], float[]> verticesAndNormals;
			try
			{
				verticesAndNormals = getMeshes[key.scaleIndex()].apply(key);
			}
			catch (final Exception e)
			{
				LOG.debug("Was not able to retrieve mesh for key {}: {}", key, e);
				synchronized (this)
				{
					if (isTaskCanceled.getAsBoolean())
					{
						// Task has been interrupted
						if (!workers.isShutdown())
							assert !tasks.containsKey(key) || tasks.get(key).tag != tag : "Task has been interrupted but it still exists in the tasks collection of size " + tasks.size() + ": " + key;
					}
					else
					{
						// Terminated because of an error
						e.printStackTrace();
						if (tasks.containsKey(key) && tasks.get(key).tag == tag)
						{
							tasks.remove(key);
							System.out.println("Early termination of task " + key);
						}
					}
				}
				return;
			}

			if (verticesAndNormals != null)
			{
				synchronized (this)
				{
					if (!isTaskCanceled.getAsBoolean())
					{
						task.state = TaskState.COMPLETED;
						onMeshGenerated(key, verticesAndNormals);
					}
				}
			}
		};

		assert blockTree.nodes.containsKey(key) : "Requested to create task for block but it's not in the tree, key: " + key;
		final double distanceFromCamera = blockTree.nodes.get(key).distanceFromCamera;

		final MeshWorkerPriority taskPriority = new MeshWorkerPriority(distanceFromCamera, key.scaleIndex());
		final Task task = new Task(withErrorPrinting(taskRunnable), taskPriority, tag);

		assert !tasks.containsKey(key) : "Trying to create new task for block but it already exists: " + key;
		tasks.put(key, task);
	}

	private synchronized void submitTasks(final Collection<ShapeKey<T>> keys)
	{
		if (keys.isEmpty())
			return;

		final Map<Runnable, MeshWorkerPriority> tasksToSubmit = new HashMap<>();
		for (final ShapeKey<T> key : keys)
		{
			final Task task = tasks.get(key);
			if (task != null && task.state == TaskState.CREATED)
			{
				task.state = TaskState.SCHEDULED;
				tasksToSubmit.put(task.task, task.priority);
			}
		}
		if (!tasksToSubmit.isEmpty())
			workers.addOrUpdateTasks(tasksToSubmit);
	}

	private synchronized void interruptTasks(final Collection<ShapeKey<T>> keys)
	{
		if (keys.isEmpty())
			return;

		final Set<Runnable> tasksToInterrupt = new HashSet<>();
		for (final ShapeKey<T> key : keys)
		{
			getMeshes[key.scaleIndex()].interruptFor(key);
			final Task task = tasks.get(key);
			if (task != null && (task.state == TaskState.SCHEDULED || task.state == TaskState.RUNNING))
			{
				task.state = TaskState.INTERRUPTED;
				tasksToInterrupt.add(task.task);
			}
		}
		if (!tasksToInterrupt.isEmpty())
			workers.removeTasks(tasksToInterrupt);
		tasks.keySet().removeAll(new ArrayList<>(keys));

		assert keys.stream().allMatch(key -> !meshesAndBlocks.containsKey(key)) : "Tasks have been interrupted but some of the blocks still exist in the scene: " +
				keys.stream().filter(meshesAndBlocks::containsKey).collect(Collectors.toSet());
	}

	private synchronized void handleMeshListChange(final MapChangeListener.Change<? extends ShapeKey<T>, ? extends Pair<MeshView, Node>> change)
	{
		final ShapeKey<T> key = change.getKey();
		assert change.wasAdded() != change.wasRemoved() : "Mesh is only supposed to be added or removed at any time but not replaced: " + key;

		if (change.wasAdded())
		{
			assert tasks.containsKey(key) : "Mesh was rendered but its task does not exist: " + key;
			final long tag = tasks.get(key).tag;
			final Runnable onMeshAdded = () -> {
				if (!managers.isShutdown())
					managers.submit(withErrorPrinting(() -> onMeshAdded(key, tag)));
			};

			if (change.getValueAdded().getA() != null || change.getValueAdded().getB() != null)
			{
				// add to the queue, call onMeshAdded() when complete
				final MeshWorkerPriority priority = tasks.get(key).priority;

				meshViewUpdateQueue.addToQueue(
						key,
						change.getValueAdded(),
						meshesAndBlocksGroups,
						onMeshAdded,
						priority
				);
			}
			else
			{
				// nothing to add, invoke the callback immediately
				onMeshAdded.run();
			}
		}

		if (change.wasRemoved() && (change.getValueRemoved().getA() != null || change.getValueRemoved().getB() != null))
		{
			// try to remove the request from the queue in case the mesh has not been added to the scene yet
			if (!meshViewUpdateQueue.removeFromQueue(key))
			{
				// was not in the queue, remove it from the scene
				InvokeOnJavaFXApplicationThread.invoke(() -> {
					meshesAndBlocksGroups.getA().getChildren().remove(change.getValueRemoved().getA());
					meshesAndBlocksGroups.getB().getChildren().remove(change.getValueRemoved().getB());
				});
			}
		}
	}

	private synchronized void onMeshGenerated(final ShapeKey<T> key, final Pair<float[], float[]> verticesAndNormals)
	{
		assert blockTree.nodes.containsKey(key) : "Mesh for block has been generated but it does not exist in the current block tree: " + key;
		assert tasks.containsKey(key) : "Mesh for block has been generated but its task does not exist: " + key;
		assert !meshesAndBlocks.containsKey(key) : "Mesh for block has been generated but it already exists in the current set of generated/visible meshes: " + key;
		LOG.debug("ID {}: block {} has been generated", identifier, key);

		final boolean nonEmptyMesh = Math.max(verticesAndNormals.getA().length, verticesAndNormals.getB().length) > 0;
		final MeshView mv = nonEmptyMesh ? makeMeshView(verticesAndNormals) : null;
		final Node blockShape = nonEmptyMesh ? createBlockShape(key) : null;
		final Pair<MeshView, Node> meshAndBlock = new ValuePair<>(mv, blockShape);
		LOG.debug("Found {}/3 vertices and {}/3 normals", verticesAndNormals.getA().length, verticesAndNormals.getB().length);

		final StatefulBlockTreeNode<ShapeKey<T>> treeNode = blockTree.nodes.get(key);
		treeNode.state = BlockTreeNodeState.RENDERED;

		if (treeNode.parentKey != null)
			assert blockTree.nodes.containsKey(treeNode.parentKey) : "Generated mesh has a parent block but it doesn't exist in the current block tree: key=" + key + ", parentKey=" + treeNode.parentKey;
		final boolean isParentBlockVisible = treeNode.parentKey != null && blockTree.nodes.get(treeNode.parentKey).state == BlockTreeNodeState.VISIBLE;

		if (isParentBlockVisible)
		{
			assert meshesAndBlocks.containsKey(treeNode.parentKey) : "Parent block of a generated mesh is in the VISIBLE state but it doesn't exist in the current set of generated/visible meshes: key=" + key + ", parentKey=" + treeNode.parentKey;
			setMeshVisibility(meshAndBlock, false);
		}

		meshesAndBlocks.put(key, meshAndBlock);
	}

	private synchronized void onMeshAdded(final ShapeKey<T> key, final long tag)
	{
		// Check if this block is still relevant.
		// The tag value is used to ensure that the block is actually relevant. Even if the task for the same key exists,
		// it might have been removed and created again, so the added block actually needs to be ignored.
		if (!tasks.containsKey(key) || tasks.get(key).state != TaskState.COMPLETED || tasks.get(key).tag != tag)
		{
			LOG.debug("ID {}: the added mesh for block {} is not relevant anymore", identifier, key);
			return;
		}

		assert blockTree.nodes.containsKey(key) : "Mesh has been added onto the scene but it does not exist in the current block tree: " + key;
		assert meshesAndBlocks.containsKey(key) : "Mesh has been added onto the scene but it does not exist in the current set of generated/visible meshes: " + key;
		LOG.debug("ID {}: mesh for block {} has been added onto the scene", identifier, key);

		tasks.remove(key);
		meshProgress.incrementNumCompletedTasks();

		final StatefulBlockTreeNode<ShapeKey<T>> treeNode = blockTree.nodes.get(key);
		assert treeNode.state == BlockTreeNodeState.RENDERED : "Mesh has been added onto the scene but the block is in the " + treeNode.state + " when it's supposed to be in the RENDERED state: " + key;

		if (!blockTree.isRoot(key))
			assert blockTree.nodes.containsKey(treeNode.parentKey) : "Added mesh has a parent block but it doesn't exist in the current block tree: key=" + key + ", parentKey=" + treeNode.parentKey;
		final boolean isParentBlockVisible = !blockTree.isRoot(key) && blockTree.nodes.get(treeNode.parentKey).state == BlockTreeNodeState.VISIBLE;

		if (isParentBlockVisible)
		{
			assert meshesAndBlocks.containsKey(treeNode.parentKey) : "Parent block of an added mesh is in the VISIBLE state but it doesn't exist in the current set of generated/visible meshes: key=" + key + ", parentKey=" + treeNode.parentKey;

			// check if all children of the parent block are ready, and if so, update their visibility and remove the parent block
			final StatefulBlockTreeNode<ShapeKey<T>> parentTreeNode = blockTree.nodes.get(treeNode.parentKey);
			treeNode.state = BlockTreeNodeState.HIDDEN;
			final boolean areAllChildrenReady = parentTreeNode.children.stream().map(blockTree.nodes::get).allMatch(childTreeNode -> childTreeNode.state == BlockTreeNodeState.HIDDEN);
			if (areAllChildrenReady)
			{
				parentTreeNode.children.forEach(childKey -> {
					blockTree.nodes.get(childKey).state = BlockTreeNodeState.VISIBLE;
					final Pair<MeshView, Node> childMeshAndBlock = meshesAndBlocks.get(childKey);
					InvokeOnJavaFXApplicationThread.invoke(() -> setMeshVisibility(childMeshAndBlock, true));
				});

				parentTreeNode.state = BlockTreeNodeState.REMOVED;
				assert !tasks.containsKey(treeNode.parentKey) : "Low-res parent block is being removed but there is a task for it: " + key;
				meshesAndBlocks.remove(treeNode.parentKey);

				// Submit tasks for next-level contained blocks
				final List<ShapeKey<T>> tasksToSubmit = new ArrayList<>();
				parentTreeNode.children.forEach(childKey -> tasksToSubmit.addAll(getPendingTasksForChildren(childKey)));
				submitTasks(tasksToSubmit);
			}
		}
		else
		{
			assert !meshesAndBlocks.containsKey(treeNode.parentKey) : "Parent block of an added mesh is not visible but it exists in the current set of generated/visible meshes: key=" + key + ", parentKey=" + treeNode.parentKey;
			if (!blockTree.isRoot(key))
			{
				final StatefulBlockTreeNode<ShapeKey<T>> parentTreeNode = blockTree.nodes.get(treeNode.parentKey);
				assert parentTreeNode.state == BlockTreeNodeState.REMOVED : "The parent block exists in the tree but is not visible, " +
						"therefore it's expected to be in the REMOVED state but it's in the " + parentTreeNode.state + " state. Key: " + key + ", parent key: " + treeNode.parentKey;
			}

			// Update the visibility of this block
			treeNode.state = BlockTreeNodeState.VISIBLE;
			final Pair<MeshView, Node> meshAndBlock = meshesAndBlocks.get(key);
			InvokeOnJavaFXApplicationThread.invoke(() -> setMeshVisibility(meshAndBlock, true));

			if (requestedBlockTree.isLeaf(key))
			{
				// Remove all children nodes that are not needed anymore: this is the case when resolution for the block is decreased,
				// and a set of higher-res blocks needs to be replaced with the single low-res block
				assert assertSubtreeToBeReplacedWithLowResBlock(key);
				blockTree.traverseSubtreeSkipRoot(key, (childKey, childNode) -> {
					assert !tasks.containsKey(childKey);
					meshesAndBlocks.remove(childKey);
					blockTree.nodes.remove(childKey);
					return true;
				});
				treeNode.children.clear();
				assert assertBlockTreeStructure(blockTree);
			}
			else
			{
				// Submit tasks for pending children in case the resolution for this block needs to increase
				submitTasks(getPendingTasksForChildren(key));
			}
		}

		if (tasks.isEmpty())
		{
			LOG.debug("All tasks are finished");
			assert assertBlockTreeStructure(blockTree) : "Resulting block tree is not valid";

			try
			{
				assert meshProgress.getNumTasks() == blockTree.nodes.size() : "Resulting block tree was supposed to have " + meshProgress.getNumTasks() + " nodes, but it has " + blockTree.nodes.size() + " nodes";
			}
			catch (final AssertionError e)
			{
				final Set<ShapeKey<T>> notInRequestedTree = new HashSet<>();
				notInRequestedTree.addAll(blockTree.nodes.keySet());
				notInRequestedTree.removeAll(requestedBlockTree.nodes.keySet());

				final Set<ShapeKey<T>> notInResultingTree = new HashSet<>();
				notInResultingTree.addAll(requestedBlockTree.nodes.keySet());
				notInResultingTree.removeAll(blockTree.nodes.keySet());

				System.err.println("*****");
				System.err.println("Requested and resulting block trees differ, notInRequestedTree: " + notInRequestedTree.size() + ", notInResultingTree: " + notInResultingTree.size());

				try (final PrintWriter writer = new PrintWriter("/groups/saalfeld/home/pisarevi/Documents/paintera-test-logs/blocktrees-update-" + sceneUpdateCounter.get() + ".txt"))
				{
					writer.println("Not in requested tree (" + notInRequestedTree.size() + " entries):");
					for (final ShapeKey<T> notInRequestedTreeKey : notInRequestedTree)
						writer.println("  " + notInRequestedTreeKey + ": " + blockTree.nodes.get(notInRequestedTreeKey));
					writer.println(System.lineSeparator());
					writer.println(System.lineSeparator());

					writer.println("Not in resulting tree (" + notInResultingTree.size() + " entries):");
					for (final ShapeKey<T> notInResultingTreeKey : notInResultingTree)
						writer.println("  " + notInResultingTreeKey + ": " + requestedBlockTree.nodes.get(notInResultingTreeKey));
					writer.println(System.lineSeparator());
					writer.println(System.lineSeparator());
					writer.println("========================================");
					writer.println(System.lineSeparator());
					writer.println(System.lineSeparator());

					writer.println("Requested block tree of size " + requestedBlockTree.nodes.size() + ":");
					for (final Entry<ShapeKey<T>, BlockTreeNode<ShapeKey<T>>> node : requestedBlockTree.nodes.entrySet())
					{
						writer.println("  " + node.getKey() + ": " + node.getValue());
						writer.println("      parent=" + node.getValue().parentKey);
						if (node.getValue().children.isEmpty()) {
							writer.println("      no children");
						} else {
							writer.println("      children:");
							for (final ShapeKey<T> childKey : node.getValue().children)
								writer.println("        " + childKey);
						}
					}
					writer.println(System.lineSeparator());
					writer.println(System.lineSeparator());

					writer.println("Resulting block tree of size " + blockTree.nodes.size() + ":");
					for (final Entry<ShapeKey<T>, StatefulBlockTreeNode<ShapeKey<T>>> node : blockTree.nodes.entrySet())
					{
						writer.println("  " + node.getKey() + ": " + node.getValue());
						writer.println("      parent=" + node.getValue().parentKey);
						if (node.getValue().children.isEmpty()) {
							writer.println("      no children");
						} else {
							writer.println("      children:");
							for (final ShapeKey<T> childKey : node.getValue().children)
								writer.println("        " + childKey);
						}
					}
				}
				catch (final IOException io)
				{
					io.printStackTrace();
				}
				throw e;
			}
		}
	}

	private synchronized List<ShapeKey<T>> getPendingTasksForChildren(final ShapeKey<T> key)
	{
		return blockTree.nodes.get(key).children.stream()
				.filter(childKey -> blockTree.nodes.get(childKey).state == BlockTreeNodeState.PENDING)
				.collect(Collectors.toList());
	}

	private void setMeshVisibility(final Pair<MeshView, Node> meshAndBlock, final boolean isVisible)
	{
		if (meshAndBlock.getA() != null)
			meshAndBlock.getA().setVisible(isVisible);

		if (meshAndBlock.getB() != null)
			meshAndBlock.getB().setVisible(isVisible);
	}

	/**
	 * Updates the scene block tree with respect to the newly requested block tree.
	 * Filters out blocks that do not need to be rendered. {@code blocksToRendered.renderListWithDistances} is modified in-place to store the filtered set.
	 *
	 * @param params
	 * @return
	 */
	private synchronized Pair<Set<ShapeKey<T>>, Integer> updateBlockTree(final SceneUpdateJobParameters params)
	{
		// Create mapping of scene tree blocks to only those that contain the current label identifier
		final BiMap<BlockTreeFlatKey, ShapeKey<T>> mapping = HashBiMap.create();
		final int highestScaleLevelInTree = params.sceneBlockTree.nodes.keySet().stream().mapToInt(key -> key.scaleLevel).min().orElse(numScaleLevels);
		for (int scaleLevel = numScaleLevels - 1; scaleLevel >= highestScaleLevelInTree; --scaleLevel)
		{
			final Interval[] containingSourceBlocks = getBlockLists[scaleLevel].apply(identifier);
			for (final Interval sourceInterval : containingSourceBlocks)
			{
				final long[] intersectingRendererBlockIndices = Grids.getIntersectingBlocks(sourceInterval, params.rendererGrids[scaleLevel]);
				for (final long intersectingRendererBlockIndex : intersectingRendererBlockIndices)
				{
					final BlockTreeFlatKey flatKey = new BlockTreeFlatKey(scaleLevel, intersectingRendererBlockIndex);
					if (!mapping.containsKey(flatKey) && params.sceneBlockTree.nodes.containsKey(flatKey))
					{
						final ShapeKey<T> shapeKey = createShapeKey(
								params.rendererGrids[scaleLevel],
								intersectingRendererBlockIndex,
								scaleLevel,
								params
						);
						mapping.put(flatKey, shapeKey);
					}
				}
			}
		}

		// Temporarily store last requested block tree and initialize the new one
		final BlockTree<ShapeKey<T>, BlockTreeNode<ShapeKey<T>>> lastRequestedBlockTree = requestedBlockTree;
		requestedBlockTree = new BlockTree<>();

		// Create complete block tree that represents new scene state for the current label identifier
		for (final Entry<BlockTreeFlatKey, ShapeKey<T>> entry : mapping.entrySet())
		{
			final BlockTreeNode<BlockTreeFlatKey> sceneTreeNode = params.sceneBlockTree.nodes.get(entry.getKey());
			final ShapeKey<T> parentKey = mapping.get(sceneTreeNode.parentKey);
			assert params.sceneBlockTree.isRoot(entry.getKey()) == (parentKey == null);
			final Set<ShapeKey<T>> children = new HashSet<>(sceneTreeNode.children.stream().map(mapping::get).filter(Objects::nonNull).collect(Collectors.toSet()));
			final BlockTreeNode<ShapeKey<T>> treeNode = new BlockTreeNode<>(parentKey, children, sceneTreeNode.distanceFromCamera);
			requestedBlockTree.nodes.put(entry.getValue(), treeNode);
		}

		// Remove leaf blocks in the label block tree that have higher-res blocks in the scene block tree
		// (this means that these lower-res parent blocks contain the "overhanging" part of the label data and should not be included)
		final Queue<ShapeKey<T>> leafKeyQueue = new ArrayDeque<>(requestedBlockTree.getLeafKeys());
		while (!leafKeyQueue.isEmpty())
		{
			final ShapeKey<T> leafShapeKey = leafKeyQueue.poll();
			final BlockTreeFlatKey leafFlatKey = mapping.inverse().get(leafShapeKey);
			assert leafFlatKey != null && params.sceneBlockTree.nodes.containsKey(leafFlatKey);
			if (!params.sceneBlockTree.isLeaf(leafFlatKey))
			{
				// This block has been subdivided in the scene tree, but the current label data doesn't list any children blocks.
				// Therefore this block needs to be excluded from the renderer block tree to avoid rendering overhanging low-res parts.
				assert requestedBlockTree.isLeaf(leafShapeKey);
				final BlockTreeNode<ShapeKey<T>> removedLeafNode = requestedBlockTree.nodes.remove(leafShapeKey);
				if (removedLeafNode.parentKey != null)
				{
					final BlockTreeNode<ShapeKey<T>> parentNode = requestedBlockTree.nodes.get(removedLeafNode.parentKey);
					assert parentNode != null && parentNode.children.contains(leafShapeKey);
					parentNode.children.remove(leafShapeKey);
					if (parentNode.children.isEmpty())
						leafKeyQueue.add(removedLeafNode.parentKey);
				}
			}
		}

		// The complete block tree for the current label id representing the new scene state is now ready
		final int numTotalBlocks = requestedBlockTree.nodes.size();
		assert assertBlockTreeStructure(requestedBlockTree) : "Requested block tree to render is not valid";

		// Initialize the tree if it was empty
		if (blockTree.nodes.isEmpty())
		{
			assert lastRequestedBlockTree == null || lastRequestedBlockTree.nodes.isEmpty() : "Current block tree is empty but the last requested tree was not empty";
			for (final Entry<ShapeKey<T>, BlockTreeNode<ShapeKey<T>>> entry : requestedBlockTree.nodes.entrySet())
			{
				final BlockTreeNode<ShapeKey<T>> node = entry.getValue();
				blockTree.nodes.put(entry.getKey(), new StatefulBlockTreeNode<>(node.parentKey, node.children, node.distanceFromCamera));
			}
			return new ValuePair<>(new HashSet<>(requestedBlockTree.nodes.keySet()), numTotalBlocks);
		}

		// For collecting blocks that are not in the current tree yet and need to be rendered
		// (including low-res parent blocks in the REMOVED state that need to be displayed in the new configuration)
		final Set<ShapeKey<T>> filteredKeysToRender = new HashSet<>();

		// For collecting blocks that will need to stay in the current tree
		final Set<ShapeKey<T>> touchedBlocks = new HashSet<>();

		// Reset the rendering of lower-resolution blocks in the current tree (according to the last requested tree)
		// if the configuration is different in the new requested tree.
		final List<ShapeKey<T>> tasksToInterrupt = new ArrayList<>();
		for (final ShapeKey<T> lastRequestedLeafKey : lastRequestedBlockTree.getLeafKeys())
		{
			assert blockTree.nodes.containsKey(lastRequestedLeafKey) : "Last requested leaf key is not present in the current block tree: " + lastRequestedLeafKey;
			final StatefulBlockTreeNode<ShapeKey<T>> treeNodeForLastRequestedLeafKey = blockTree.nodes.get(lastRequestedLeafKey);
			if (blockTree.isLeaf(lastRequestedLeafKey))
			{
				// The last requested leaf node is also a leaf node in the current tree.
				assert treeNodeForLastRequestedLeafKey.state != BlockTreeNodeState.REMOVED;
			}
			else
			{
				// The last requested leaf node has children in the current tree from the previous configurations.
				// This means that it's still being rendered in order to replace a set of higher-res children blocks with the lower-res parent block.
				assert treeNodeForLastRequestedLeafKey.state == BlockTreeNodeState.PENDING || treeNodeForLastRequestedLeafKey.state == BlockTreeNodeState.RENDERED :
						"This node was the leaf node in the last requested tree and it hasn't been rendered yet, " +
						"therefore it's expected to be in either PENDING or RENDERED state, but it was in " +
						treeNodeForLastRequestedLeafKey.state + " state. Key: " + lastRequestedLeafKey;

				// Verify that the subtree only consists of the nodes in the REMOVED and VISIBLE state, where the nodes in the VISIBLE are the leaf nodes
				assert assertSubtreeToBeReplacedWithLowResBlock(lastRequestedLeafKey);

				// Verify that all ancestors are in valid state
				assert assertAncestorsOfSubtreeToBeReplacedWithLowResBlock(lastRequestedLeafKey);

				// Compare the current configuration against the newly requested configuration
				if (!requestedBlockTree.isLeaf(lastRequestedLeafKey))
				{
					// The block doesn't exist in the newly requested tree. Abort the rendering task for this low-res block
					// because it will be replaced with even lower-res block.
					tasksToInterrupt.add(lastRequestedLeafKey);
					meshesAndBlocks.remove(lastRequestedLeafKey);
					treeNodeForLastRequestedLeafKey.state = BlockTreeNodeState.REMOVED;
				}
			}
		}
		interruptTasks(tasksToInterrupt);

		// Intersect the current block tree with the new requested tree, starting the traversal from the leaf nodes of the new tree
		for (final ShapeKey<T> newRequestedLeafKey : requestedBlockTree.getLeafKeys())
		{
			// Check if the new leaf node is contained in the current tree
			if (blockTree.nodes.containsKey(newRequestedLeafKey))
			{
				final StatefulBlockTreeNode<ShapeKey<T>> treeNodeForNewLeafKey = blockTree.nodes.get(newRequestedLeafKey);
				if (blockTree.isLeaf(newRequestedLeafKey))
				{
					// The leaf block in the requested tree is also the leaf block in the current tree, no subtree traversal is necessary
					assert treeNodeForNewLeafKey.state != BlockTreeNodeState.REMOVED;
				}
				else
				{
					// Decreasing the resolution of this subtree.
					boolean keepSubtreeBlocks = false;
					if (lastRequestedBlockTree.isLeaf(newRequestedLeafKey))
					{
						// This block was also the leaf in the previously requested configuration, so no update should be needed.
						assert treeNodeForNewLeafKey.state == BlockTreeNodeState.PENDING || treeNodeForNewLeafKey.state == BlockTreeNodeState.RENDERED;
						assert assertSubtreeToBeReplacedWithLowResBlock(newRequestedLeafKey);
						keepSubtreeBlocks = true;
					}
					else if (treeNodeForNewLeafKey.state == BlockTreeNodeState.REMOVED)
					{
						// Mark this block as to-be-rendered
						treeNodeForNewLeafKey.state = BlockTreeNodeState.PENDING;
						filteredKeysToRender.add(newRequestedLeafKey);
						keepSubtreeBlocks = true;
					}

					if (keepSubtreeBlocks)
					{
						// Keep the currently visible higher-res blocks in the subtree and mark them as to-be-removed once this low-res block is ready
						blockTree.traverseSubtreeSkipRoot(newRequestedLeafKey, (childKey, childNode) -> {
							if (childNode.state == BlockTreeNodeState.REMOVED)
							{
								touchedBlocks.add(childKey);
								// Check that a subtree exists
								assert !blockTree.isLeaf(childKey) : "A state of the block in the tree says that there supposed to be a subtree with visible blocks, " +
										"but the block is a leaf node: " + childNode + ", key: " + childKey;
								return true;
							}
							else if (childNode.state == BlockTreeNodeState.VISIBLE)
							{
								touchedBlocks.add(childKey);
								// Check that there are no REMOVED or VISIBLE blocks in the subtree
								assert assertSubtreeOfVisibleBlock(childKey);
								return false;
							}
							return false;
						});
					}
				}
			}
			else
			{
				// Block is not in the current tree yet, need to add this block and all of its ancestors.
				// This adds remaining nodes in the tree and required blocks to the to-be-rendered list
				final ObjectProperty<ShapeKey<T>> lastChildKey = new SimpleObjectProperty<>();
				requestedBlockTree.traverseAncestors(newRequestedLeafKey, (requestedAncestorKey, requestedAncestorNode) -> {
					if (!blockTree.nodes.containsKey(requestedAncestorKey))
					{
						// The block is not in the tree yet, insert it and add the block to the render list
						filteredKeysToRender.add(requestedAncestorKey);
						final double distanceFromCamera = requestedAncestorNode.distanceFromCamera;
						blockTree.nodes.put(requestedAncestorKey, new StatefulBlockTreeNode<>(requestedAncestorNode.parentKey, new HashSet<>(), distanceFromCamera));
					}

					if (lastChildKey.get() != null)
						blockTree.nodes.get(requestedAncestorKey).children.add(lastChildKey.get());
					lastChildKey.set(requestedAncestorKey);
				});
			}

			// Mark the block and all of its ancestors to be kept in the tree
			requestedBlockTree.traverseAncestors(newRequestedLeafKey, (ancestorKey, ancestorNode) -> touchedBlocks.add(ancestorKey));
		}

		// Remove unneeded blocks from the tree
		blockTree.nodes.keySet().retainAll(touchedBlocks);
		for (final StatefulBlockTreeNode<ShapeKey<T>> treeNode : blockTree.nodes.values())
			treeNode.children.retainAll(touchedBlocks);

		// Update distances from the camera for each block in the new tree
		for (final Entry<ShapeKey<T>, StatefulBlockTreeNode<ShapeKey<T>>> entry : blockTree.nodes.entrySet())
		{
			final BlockTreeNode<ShapeKey<T>> requestedBlockTreeNode = requestedBlockTree.nodes.get(entry.getKey());
			entry.getValue().distanceFromCamera = requestedBlockTreeNode != null ? requestedBlockTreeNode.distanceFromCamera : Double.POSITIVE_INFINITY;
		}

		// The current tree should include all the newly requested nodes at this point
		assert requestedBlockTree.nodes.keySet().stream().allMatch(blockTree.nodes::containsKey) : "The scene block tree is supposed to include all of the nodes in the requested tree";
		assert assertBlockTreeStructure(blockTree) : "The updated scene block tree is not valid";

		// Filter the rendering list and retain only necessary keys to be rendered
		return new ValuePair<>(filteredKeysToRender, numTotalBlocks);
	}

	private ShapeKey<T> createShapeKey(
			final CellGrid grid,
			final long index,
			final int scaleLevel,
			final SceneUpdateJobParameters params)
	{
		final Interval blockInterval = Grids.getCellInterval(grid, index);
		return new ShapeKey<>(
				identifier,
				scaleLevel,
				params.simplificationIterations,
				params.smoothingLambda,
				params.smoothingIterations,
				params.minLabelRatio,
				Intervals.minAsLongArray(blockInterval),
				Intervals.maxAsLongArray(blockInterval)
			);
	}

	private static MeshView makeMeshView(final Pair<float[], float[]> verticesAndNormals)
	{
		final float[]      vertices = verticesAndNormals.getA();
		final float[]      normals  = verticesAndNormals.getB();
		final TriangleMesh mesh     = new TriangleMesh();
		mesh.getPoints().addAll(vertices);
		mesh.getNormals().addAll(normals);
		mesh.getTexCoords().addAll(0, 0);
		mesh.setVertexFormat(VertexFormat.POINT_NORMAL_TEXCOORD);
		final int[] faceIndices = new int[vertices.length];
		for (int i = 0, k = 0; i < faceIndices.length; i += 3, ++k)
		{
			faceIndices[i + 0] = k;
			faceIndices[i + 1] = k;
			faceIndices[i + 2] = 0;
		}
		mesh.getFaces().addAll(faceIndices);
		final PhongMaterial material = Meshes.painteraPhongMaterial();
		final MeshView mv = new MeshView(mesh);
		mv.setOpacity(1.0);
		mv.setCullFace(CullFace.FRONT);
		mv.setMaterial(material);
		mv.setDrawMode(DrawMode.FILL);
		return mv;
	}

	private Node createBlockShape(final ShapeKey<T> key)
	{
		final Interval keyInterval = key.interval();
		final double[] worldMin = new double[3], worldMax = new double[3];
		Arrays.setAll(worldMin, d -> keyInterval.min(d));
		Arrays.setAll(worldMax, d -> keyInterval.min(d) + keyInterval.dimension(d));
		unshiftedWorldTransforms[key.scaleIndex()].apply(worldMin, worldMin);
		unshiftedWorldTransforms[key.scaleIndex()].apply(worldMax, worldMax);

		final RealInterval blockWorldInterval = new FinalRealInterval(worldMin, worldMax);
		final double[] blockWorldSize = new double[blockWorldInterval.numDimensions()];
		Arrays.setAll(blockWorldSize, d -> blockWorldInterval.realMax(d) - blockWorldInterval.realMin(d));

		// the standard Box primitive is made up of triangles, so the unwanted diagonals are visible when using DrawMode.Line
//		final Box box = new Box(
//				blockWorldSize[0],
//				blockWorldSize[1],
//				blockWorldSize[2]
//			);
		final PolygonMeshView box = new PolygonMeshView(Meshes.createQuadrilateralMesh(
				(float) blockWorldSize[0],
				(float) blockWorldSize[1],
				(float) blockWorldSize[2]
		));

		final double[] blockWorldTranslation = new double[blockWorldInterval.numDimensions()];
		Arrays.setAll(blockWorldTranslation, d -> blockWorldInterval.realMin(d) + blockWorldSize[d] * 0.5);

		box.setTranslateX(blockWorldTranslation[0]);
		box.setTranslateY(blockWorldTranslation[1]);
		box.setTranslateZ(blockWorldTranslation[2]);

		final PhongMaterial material = Meshes.painteraPhongMaterial();
		box.setCullFace(CullFace.NONE);
		box.setMaterial(material);
		box.setDrawMode(DrawMode.LINE);

		return box;
	}

	private static Runnable withErrorPrinting(final Runnable runnable)
	{
		return () -> {
			try {
				runnable.run();
			} catch (final RejectedExecutionException e) {
				// this happens when the application is being shut down and is normal, don't do anything
			} catch (final Throwable e) {
				e.printStackTrace();
			}
		};
	}

	private synchronized <K, N extends BlockTreeNode<K>> boolean assertBlockTreeStructure(final BlockTree<K, N> tree)
	{
		for (final Map.Entry<K, N> entry : tree.nodes.entrySet())
		{
			final K key = entry.getKey();
			final N node = entry.getValue();

			if (node.parentKey != null)
			{
				// validate parent
				assert tree.nodes.containsKey(node.parentKey) : "Tree doesn't contain parent node with key " + node.parentKey + ", child key: " + key;
				assert tree.nodes.get(node.parentKey).children.contains(key) : "Parent node with key " + node.parentKey + " doesn't list the node with key " + key + " as its child";
			}

			// validate children
			for (final K childKey : node.children)
			{
				assert tree.nodes.containsKey(childKey) : "Tree doesn't contain child node with key " + childKey + ", parent key: " + key;
				assert tree.nodes.get(childKey).parentKey.equals(key) : "Parent key of the child node is not consistent with the parent node's children list: " +
						"child key: " + childKey + ", child node's parent key: " + tree.nodes.get(childKey).parentKey + ", actual parent key: " + key;
			}
		}
		return true;
	}

	private synchronized boolean assertBlockTreeStates()
	{
		final EnumSet<BlockTreeNodeState> sceneBlockStates = EnumSet.of(
				BlockTreeNodeState.VISIBLE,
				BlockTreeNodeState.RENDERED,
				BlockTreeNodeState.HIDDEN
			);

		meshesAndBlocks.keySet().stream().allMatch(key -> {
			final StatefulBlockTreeNode<ShapeKey<T>> node = blockTree.nodes.get(key);
			assert sceneBlockStates.contains(node.state) : "A block that is currently in the scene is not in one of the valid states: " + node + ", key: " + key;
			return true;
		});

		final Set<ShapeKey<T>> notInScene = new HashSet<>(blockTree.nodes.keySet());
		notInScene.removeAll(meshesAndBlocks.keySet());
		notInScene.forEach(key -> {
			final StatefulBlockTreeNode<ShapeKey<T>> node = blockTree.nodes.get(key);
			assert !sceneBlockStates.contains(node.state) : "A block that is currently not in the scene is not in one of the valid states: " + node + ", key: " + key;
		});

		blockTree.getRootKeys().forEach(rootKey -> {
			final Set<ShapeKey<T>> visibleBlockKeys = new HashSet<>();
			blockTree.traverseSubtree(rootKey, (childKey, childNode) -> {
				if (!blockTree.isLeaf(childKey) && requestedBlockTree.isLeaf(childKey))
				{
					// Replacing high-res subtree with a low-res block
					assert childNode.state == BlockTreeNodeState.PENDING || childNode.state == BlockTreeNodeState.RENDERED :
							"Low-res block is not ready yet and is expected to be in either PENDING or RENDERED state, " +
							"but it was in " + childNode.state + " state, key: " + childKey;
					assertSubtreeToBeReplacedWithLowResBlock(childKey);
					return false;
				}
				else if (childNode.state == BlockTreeNodeState.PENDING || childNode.state == BlockTreeNodeState.RENDERED || childNode.state == BlockTreeNodeState.HIDDEN)
				{
					if (childNode.state == BlockTreeNodeState.HIDDEN)
					{
						// Check that there are nodes at the same level that are still pending
						assert visibleBlockKeys.contains(childNode.parentKey);
						assert blockTree.getChildrenNodes(childNode.parentKey).stream().anyMatch(node -> node.state == BlockTreeNodeState.PENDING || node.state == BlockTreeNodeState.RENDERED) :
								"Node is in the HIDDEN state, therefore some of the nodes with the same parent key are not ready yet and have to be in either PENDING or RENDERED state," +
								"but all of them have different states";
					}
					// Gradually increasing the resolution, all descendants must be in the PENDING state
					blockTree.traverseSubtreeSkipRoot(childKey, (descendantKey, descendantNode) -> {
						assert descendantNode.state == BlockTreeNodeState.PENDING : "Gradually increasing the resolution, all descendants must be in the PENDING state, " +
								"but one of the nodes was in " + descendantNode.state + " state, key: " + descendantKey;
						return true;
					});
					return false;
				}
				else if (childNode.state == BlockTreeNodeState.VISIBLE)
				{
					assert !visibleBlockKeys.contains(childNode.parentKey) : "Parent blocks of the visible blocks should not be visible";
					// Continue evaluating the states of the subtrees, additionally checking that no blocks in the subtrees are in the VISIBLE state
					visibleBlockKeys.add(childKey);
					return true;
				}
				else if (childNode.state == BlockTreeNodeState.REMOVED)
				{
					assert !visibleBlockKeys.contains(childNode.parentKey) : "Block is in the REMOVED state, but its parent is in VISIBLE state";
					// Simply continue evaluating the states of the subtrees
					return true;
				}
				else
					throw new AssertionError("Unknown block tree node state: " + childNode.state);
			});
		});

		return true;
	}

	private synchronized boolean assertSubtreeOfVisibleBlock(final ShapeKey<T> visibleBlockKey)
	{
		assert blockTree.nodes.get(visibleBlockKey).state == BlockTreeNodeState.VISIBLE;
		blockTree.traverseSubtreeSkipRoot(visibleBlockKey, (childKey, childNode) -> {
			final boolean isValid = childNode.state != BlockTreeNodeState.VISIBLE && childNode.state != BlockTreeNodeState.REMOVED;
			assert isValid : "Validation of the subtree of a visible block failed: " +
					"a node in the subtree is not in the valid state: " + childNode + ", key: " + childKey;
			return true;
		});
		return true;
	}

	private synchronized boolean assertSubtreeToBeReplacedWithLowResBlock(final ShapeKey<T> lastRequestedLeafKey)
	{
		blockTree.traverseSubtreeSkipRoot(lastRequestedLeafKey, (childKey, childNode) -> {
			final boolean isValid = blockTree.isLeaf(childKey) ? childNode.state == BlockTreeNodeState.VISIBLE : childNode.state == BlockTreeNodeState.REMOVED;
			assert isValid : "Validation of the subtree that needs to be replaced with a low-res parent block failed: " +
					"a node in the subtree is not in the valid state: " + childNode + ", key: " + childKey;
			return true;
		});
		return true;
	}

	private synchronized boolean assertAncestorsOfSubtreeToBeReplacedWithLowResBlock(final ShapeKey<T> lastRequestedLeafKey)
	{
		final StatefulBlockTreeNode<ShapeKey<T>> node = blockTree.nodes.get(lastRequestedLeafKey);
		blockTree.traverseAncestors(node.parentKey, (ancestorKey, ancestorNode) -> {
			assert ancestorNode.state == BlockTreeNodeState.REMOVED : "Validation of the ancestors of the subtree that needs to be replaced with a low-res parent block failed: " +
					"an ancestor node is not in the valid state: " + ancestorNode + ", key: " + ancestorKey;
		});
		return true;
	}
}
