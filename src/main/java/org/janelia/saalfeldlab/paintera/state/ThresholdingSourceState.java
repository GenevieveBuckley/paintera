package org.janelia.saalfeldlab.paintera.state;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableDoubleValue;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import net.imglib2.Volatile;
import net.imglib2.converter.Converter;
import net.imglib2.type.BooleanType;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.AbstractVolatileRealType;
import org.janelia.saalfeldlab.paintera.PainteraBaseView;
import org.janelia.saalfeldlab.paintera.composition.ARGBCompositeAlphaAdd;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.PredicateDataSource;
import org.janelia.saalfeldlab.paintera.meshes.MeshSettings;
import org.janelia.saalfeldlab.paintera.meshes.MeshesFromBooleanData;
import org.janelia.saalfeldlab.paintera.state.ThresholdingSourceState.Threshold;
import org.janelia.saalfeldlab.paintera.state.ThresholdingSourceState.VolatileMaskConverter;
import org.janelia.saalfeldlab.paintera.state.predicate.threshold.Bounds;
import org.janelia.saalfeldlab.util.Colors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.function.Predicate;

public class ThresholdingSourceState<D extends RealType<D>, T extends AbstractVolatileRealType<D, T>>
		extends
		MinimalSourceState<BoolType, Volatile<BoolType>, PredicateDataSource<D, T, Threshold<D>>,
				VolatileMaskConverter<BoolType, Volatile<BoolType>>> {

	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final ObjectProperty<Color> color = new SimpleObjectProperty<>(Color.WHITE);

	private final ObjectProperty<Color> backgroundColor = new SimpleObjectProperty<>(Color.BLACK);

	private final DoubleProperty alpha = new SimpleDoubleProperty(1.0);

	private final Threshold<D> threshold;

	private final SourceState<D, T> underlyingSource;

	private final DoubleProperty min = new SimpleDoubleProperty();

	private final DoubleProperty max = new SimpleDoubleProperty();

	private MeshesFromBooleanData<BoolType, Bounds> meshes = null;

	private final MeshSettings meshSettings;

	private final BooleanProperty areMeshesEnabled = new SimpleBooleanProperty();

	public ThresholdingSourceState(
			final String name,
			final SourceState<D, T> toBeThresholded)
	{
		super(
				threshold(toBeThresholded.getDataSource(), name),
				new VolatileMaskConverter<>(),
				new ARGBCompositeAlphaAdd(),
				name,
				toBeThresholded);
		this.threshold = getDataSource().getPredicate();
		this.underlyingSource = toBeThresholded;
		this.axisOrderProperty().bindBidirectional(toBeThresholded.axisOrderProperty());
		this.color.addListener((obs, oldv, newv) -> converter().setMasked(Colors.toARGBType(newv)));
		this.backgroundColor.addListener((obs, oldv, newv) -> converter().setNotMasked(Colors.toARGBType(newv)));
		threshold.minSupplier.bind(min);
		threshold.maxSupplier.bind(max);

		final D d = underlyingSource.getDataSource().getDataType();
		if (d instanceof IntegerType<?>) {
			this.min.set(d.getMinValue());
			this.max.set(d.getMaxValue());
		} else {
			this.min.set(0.0);
			this.max.set(1.0);
		}

		min.addListener((obs, oldv, newv) -> setMeshId(this.meshes));
		max.addListener((obs, oldv, newv) -> setMeshId(this.meshes));

		this.meshSettings = new MeshSettings(getDataSource().getNumMipmapLevels());
		this.areMeshesEnabled.addListener((obs, oldv, newv) -> {
			if (this.meshes != null)
				this.meshes.setEnabled(newv);
		});

	}

	// could remove this and just expose actualMin, actualMax
	public Threshold<D> getThreshold()
	{
		return this.threshold;
	}

	private static <D extends RealType<D>, T extends AbstractVolatileRealType<D, T>> PredicateDataSource<D, T,
			Threshold<D>> threshold(
			final DataSource<D, T> source,
			final String name)
	{
		return new PredicateDataSource<>(source, new Threshold<>(), name);
	}

	public ObjectProperty<Color> colorProperty()
	{
		return this.color;
	}
	public ObjectProperty<Color> backgroundColorProperty() {
		return this.backgroundColor;
	}

	public DoubleProperty alphaProperty()
	{
		return this.alpha;
	}

	public static class MaskConverter<B extends BooleanType<B>> implements Converter<B, ARGBType>
	{

		private final ARGBType masked = Colors.toARGBType(Color.rgb(255, 255, 255, 1.0));

		private final ARGBType notMasked = Colors.toARGBType(Color.rgb(0, 0, 0, 0.0));

		@Override
		public void convert(final B mask, final ARGBType color)
		{
			color.set(mask.get() ? masked : notMasked);
		}

		public void setMasked(final ARGBType masked)
		{
			this.masked.set(masked);
		}

		public void setNotMasked(final ARGBType notMasked)
		{
			this.notMasked.set(masked);
		}

	}

	public static class VolatileMaskConverter<B extends BooleanType<B>, V extends Volatile<B>>
			implements Converter<V, ARGBType>
	{

		private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

		private final ARGBType masked = Colors.toARGBType(Color.rgb(255, 255, 255, 1.0));

		private final ARGBType notMasked = Colors.toARGBType(Color.rgb(0, 0, 0, 0.0));

		@Override
		public void convert(final V mask, final ARGBType color)
		{
			color.set(mask.get().get() ? masked : notMasked);
		}

		public void setMasked(final ARGBType masked)
		{
			this.masked.set(masked);
		}

		public void setNotMasked(final ARGBType notMasked)
		{
			this.notMasked.set(notMasked);
		}

		public ARGBType getMasked()
		{
			return masked;
		}

		public ARGBType getNotMasked()
		{
			return notMasked;
		}
	}

	public static class Threshold<T extends RealType<T>> implements Predicate<T>
	{
		private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

		private double min;

		private double max;

		private final DoubleProperty minSupplier = new SimpleDoubleProperty();

		private final DoubleProperty maxSupplier = new SimpleDoubleProperty();

		public Threshold()
		{
			super();
			this.minSupplier.addListener((obs, oldv, newv) -> update());
			this.maxSupplier.addListener((obs, oldv, newv) -> update());
			update();
		}

		@Override
		public boolean test(final T t)
		{
			final double  val            = t.getRealDouble();
			final boolean isWithinMinMax = val < this.max && val > this.min;
			return isWithinMinMax;
		}

		private void update()
		{
			final double m = this.minSupplier.get();
			final double M = this.maxSupplier.get();

			if (m < M)
			{
				this.min = m;
				this.max = M;
			}
			else
			{
				this.min = M;
				this.max = m;
			}
		}

		public ObservableDoubleValue minValue()
		{
			return this.minSupplier;
		}

		public ObservableDoubleValue maxValue()
		{
			return this.maxSupplier;
		}


	}

	private SourceState<D, T> getUnderlyingSource() {
		return this.underlyingSource;
	}

	public DoubleProperty minProperty() {
		return min;
	}

	public DoubleProperty maxProperty() {
		return max;
	}

	public MeshSettings getMeshSettings() {
		return this.meshSettings;
	}

	public BooleanProperty areMeshesEnabledProperty() {
		return areMeshesEnabled;
	}

	public boolean areMeshesEnabled() {
		return areMeshesEnabled.get();
	}

	public void setMeshesEnabled(final boolean areMeshesEnabled) {
		this.areMeshesEnabled.set(areMeshesEnabled);
	}

	public void refreshMeshes() {
		final MeshesFromBooleanData<?, ?> meshes = this.meshes;
		if (meshes != null)
		meshes.refreshMeshes();
	}

	@Override
	public void onAdd(final PainteraBaseView paintera) {
		color.addListener(obs -> paintera.orthogonalViews().requestRepaint());
		backgroundColor.addListener(obs -> paintera.orthogonalViews().requestRepaint());
		min.addListener(obs -> paintera.orthogonalViews().requestRepaint());
		max.addListener(obs -> paintera.orthogonalViews().requestRepaint());

		// add meshes to viewer
		this.meshes = MeshesFromBooleanData.fromSourceAndBlockSize(
				getDataSource(),
				new int[] {32, 32, 32},
				paintera.getMeshManagerExecutorService(),
				paintera.getMeshWorkerExecutorService(),
				this.meshSettings);
		this.meshes.setEnabled(this.areMeshesEnabled.get());
		paintera.viewer3D().meshesGroup().getChildren().add(this.meshes.getMeshesGroup());
		this.meshes.colorProperty().bind(this.color);
	}

	@Override
	public Node preferencePaneNode() {
		return new ThresholdingSourceStatePreferencePaneNode(this).getNode();
	}

	private void setMeshId(final MeshesFromBooleanData<?, Bounds> meshes) {
		if (meshes == null)
			return;
		meshes.setId(new Bounds(min.doubleValue(), max.doubleValue()));
	}

}
