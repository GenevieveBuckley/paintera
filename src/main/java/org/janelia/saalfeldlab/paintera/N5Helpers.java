package org.janelia.saalfeldlab.paintera;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import bdv.util.volatiles.VolatileTypeMatcher;
import bdv.viewer.Interpolation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import gnu.trove.map.TLongLongMap;
import gnu.trove.map.hash.TLongLongHashMap;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.volatiles.VolatileCache;
import net.imglib2.img.NativeImg;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.label.Label;
import net.imglib2.type.label.LabelMultisetType;
import net.imglib2.type.label.N5CacheLoader;
import net.imglib2.type.label.VolatileLabelMultisetArray;
import net.imglib2.type.label.VolatileLabelMultisetType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.*;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Triple;
import net.imglib2.util.ValueTriple;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.labels.blocks.LabelBlockLookup;
import org.janelia.saalfeldlab.labels.blocks.LabelBlockLookupAdapter;
import org.janelia.saalfeldlab.labels.blocks.LabelBlockLookupFromFile;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.LongArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5CellLoader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.paintera.cache.Invalidate;
import org.janelia.saalfeldlab.paintera.cache.global.GlobalCache;
import org.janelia.saalfeldlab.paintera.cache.global.InvalidAccessException;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignmentOnlyLocal;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignmentOnlyLocal.Persister;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignmentState;
import org.janelia.saalfeldlab.paintera.control.assignment.UnableToPersist;
import org.janelia.saalfeldlab.paintera.data.DataSource;
import org.janelia.saalfeldlab.paintera.data.axisorder.AxisOrder;
import org.janelia.saalfeldlab.paintera.data.axisorder.AxisOrderNotSupported;
import org.janelia.saalfeldlab.paintera.data.n5.N5DataSource;
import org.janelia.saalfeldlab.paintera.data.n5.N5FSMeta;
import org.janelia.saalfeldlab.paintera.data.n5.N5HDF5Meta;
import org.janelia.saalfeldlab.paintera.data.n5.N5Meta;
import org.janelia.saalfeldlab.paintera.data.n5.ReflectionException;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.janelia.saalfeldlab.paintera.id.N5IdService;
import org.janelia.saalfeldlab.paintera.ui.opendialog.VolatileHelpers;
import org.janelia.saalfeldlab.util.MakeUnchecked;
import org.janelia.saalfeldlab.util.MakeUnchecked.CheckedConsumer;
import org.janelia.saalfeldlab.util.NamedThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class N5Helpers
{

	public static final String MULTI_SCALE_KEY = "multiScale";

	public static final String IS_LABEL_MULTISET_KEY = "isLabelMultiset";

	public static final String MAX_ID_KEY = "maxId";

	public static final String RESOLUTION_KEY = "resolution";

	public static final String OFFSET_KEY = "offset";

	public static final String DOWNSAMPLING_FACTORS_KEY = "downsamplingFactors";

	public static final String LABEL_MULTISETTYPE_KEY = "isLabelMultiset";

	public static final String MAX_NUM_ENTRIES_KEY = "maxNumEntries";

	public static final String PAINTERA_DATA_KEY = "painteraData";

	public static final String PAINTERA_DATA_DATASET = "data";

	public static final String PAINTERA_FRAGMENT_SEGMENT_ASSIGNMENT_DATASTE = "fragment-segment-assignment";

	public static final String LABEL_TO_BLOCK_MAPPING = "label-to-block-mapping";

	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final int MAX_NUM_CACHE_ENTRIES = 100;

	public static class ImagesWithInvalidate<D, T> {
		public final RandomAccessibleInterval<D> data;

		public final RandomAccessibleInterval<T> vdata;

		public final AffineTransform3D transform;

		public final Invalidate<Long> invalidate;

		public final Invalidate<Long> vinvalidate;

		public ImagesWithInvalidate(
				final RandomAccessibleInterval<D> data,
				final RandomAccessibleInterval<T> vdata,
				final AffineTransform3D transform,
				final Invalidate<Long> invalidate,
				final Invalidate<Long> vinvalidate) {
			this.data = data;
			this.vdata = vdata;
			this.transform = transform;
			this.invalidate = invalidate;
			this.vinvalidate = vinvalidate;
		}
	}

	public static boolean isIntegerType(final DataType type)
	{
		switch (type)
		{
			case INT8:
			case INT16:
			case INT32:
			case INT64:
			case UINT8:
			case UINT16:
			case UINT32:
			case UINT64:
				return true;
			default:
				return false;
		}
	}

	public static boolean isPainteraDataset(final N5Reader n5, final String group) throws IOException
	{
		return n5.exists(group) && n5.listAttributes(group).containsKey(PAINTERA_DATA_KEY);
	}

	public static boolean isMultiScale(final N5Reader n5, final String dataset) throws IOException
	{
		/* based on attribute */
		boolean isMultiScale = Optional.ofNullable(n5.getAttribute(dataset, MULTI_SCALE_KEY, Boolean.class)).orElse(
				false);

		/*
		 * based on groupd content (the old way) TODO conider removing as
		 * multi-scale declaration by attribute becomes part of the N5 spec.
		 */
		if (!isMultiScale && !n5.datasetExists(dataset))
		{
			final String[] groups = n5.list(dataset);
			isMultiScale = groups.length > 0;
			for (final String group : groups)
			{
				if (!(group.matches("^s[0-9]+$") && n5.datasetExists(dataset + "/" + group)))
				{
					isMultiScale = false;
					break;
				}
			}
			if (isMultiScale)
			{
				LOG.debug(
						"Found multi-scale group without {} tag. Implicit multi-scale detection will be removed in " +
								"the" +
								" future. Please add \"{}\":{} to attributes.json.",
						MULTI_SCALE_KEY,
						MULTI_SCALE_KEY,
						true
				        );
			}
		}
		return isMultiScale;
	}

	public static boolean isLabelMultisetType(final N5Reader n5, final String dataset, final boolean isMultiscale)
	throws IOException
	{
		return isMultiscale && isLabelMultisetType(n5, getFinestLevel(n5, dataset))
				|| Optional.ofNullable(n5.getAttribute(dataset, IS_LABEL_MULTISET_KEY, Boolean.class)).orElse(false);
	}

	public static boolean isLabelMultisetType(final N5Reader n5, final String dataset) throws IOException
	{
		return Optional.ofNullable(n5.getAttribute(dataset, IS_LABEL_MULTISET_KEY, Boolean.class)).orElse(false);
	}

	public static double minForType(final DataType t)
	{
		// TODO ever return non-zero here?
		switch (t)
		{
			default:
				return 0.0;
		}
	}

	public static double maxForType(final DataType t)
	{
		switch (t)
		{
			case UINT8:
				return 0xff;
			case UINT16:
				return 0xffff;
			case UINT32:
				return 0xffffffffl;
			case UINT64:
				return 2.0 * Long.MAX_VALUE;
			case INT8:
				return Byte.MAX_VALUE;
			case INT16:
				return Short.MAX_VALUE;
			case INT32:
				return Integer.MAX_VALUE;
			case INT64:
				return Long.MAX_VALUE;
			case FLOAT32:
			case FLOAT64:
				return 1.0;
			default:
				return 1.0;
		}
	}

	public static String[] listScaleDatasets(final N5Reader n5, final String group) throws IOException
	{
		final String[] scaleDirs = Arrays
				.stream(n5.list(group))
				.filter(s -> s.matches("^s\\d+$"))
				.filter(s -> {
					try
					{
						return n5.datasetExists(group + "/" + s);
					} catch (final IOException e)
					{
						return false;
					}
				})
				.toArray(String[]::new);

		LOG.debug("Found these scale dirs: {}", Arrays.toString(scaleDirs));
		return scaleDirs;
	}

	public static String[] listAndSortScaleDatasets(final N5Reader n5, final String group) throws IOException
	{
		final String[] scaleDirs = listScaleDatasets(n5, group);
		sortScaleDatasets(scaleDirs);

		LOG.debug("Sorted scale dirs: {}", Arrays.toString(scaleDirs));
		return scaleDirs;
	}

	public static DataType getDataType(final N5Reader n5, final String group) throws IOException
	{
		LOG.debug("Getting data type for group/dataset {}", group);
		if (isPainteraDataset(n5, group)) { return getDataType(n5, group + "/" + PAINTERA_DATA_DATASET); }
		if (isMultiScale(n5, group)) { return getDataType(n5, getFinestLevel(n5, group)); }
		return n5.getDatasetAttributes(group).getDataType();
	}

	public static DatasetAttributes getDatasetAttributes(final N5Reader n5, final String group) throws IOException
	{
		LOG.debug("Getting data type for group/dataset {}", group);
		if (isPainteraDataset(n5, group)) { return getDatasetAttributes(n5, group + "/" + PAINTERA_DATA_DATASET); }
		if (isMultiScale(n5, group)) { return getDatasetAttributes(n5, getFinestLevel(n5, group)); }
		return n5.getDatasetAttributes(group);
	}

	public static void sortScaleDatasets(final String[] scaleDatasets)
	{
		Arrays.sort(scaleDatasets, (f1, f2) -> {
			return Integer.compare(
					Integer.parseInt(f1.replaceAll("[^\\d]", "")),
					Integer.parseInt(f2.replaceAll("[^\\d]", ""))
			                      );
		});
	}

	public static N5Reader n5Reader(final String base, final int... defaultCellDimensions) throws IOException
	{
		return isHDF(base) ? new N5HDF5Reader(base, defaultCellDimensions) : new N5FSReader(base);
	}

	public static N5Reader n5Reader(final String base, final GsonBuilder gsonBuilder, final int...
			defaultCellDimensions)
	throws IOException
	{
		return isHDF(base) ? new N5HDF5Reader(base, defaultCellDimensions) : new N5FSReader(base, gsonBuilder);
	}

	public static N5Writer n5Writer(final String base, final int... defaultCellDimensions) throws IOException
	{
		return isHDF(base) ? new N5HDF5Writer(base, defaultCellDimensions) : new N5FSWriter(base);
	}

	public static N5Writer n5Writer(final String base, final GsonBuilder gsonBuilder, final int...
			defaultCellDimensions)
	throws IOException
	{
		return isHDF(base) ? new N5HDF5Writer(base, defaultCellDimensions) : new N5FSWriter(base, gsonBuilder);
	}

	public static N5Meta metaData(final String base, final String dataset, final int... defaultCellDimensions)
	{
		return isHDF(base) ? new N5HDF5Meta(base, dataset, defaultCellDimensions, false) : new N5FSMeta(base, dataset);
	}

	public static boolean isHDF(final String base)
	{
		LOG.debug("Checking {} for HDF", base);
		final boolean isHDF = Pattern.matches("^h5://", base) || Pattern.matches("^.*\\.(hdf|h5)$", base);
		LOG.debug("{} is hdf5? {}", base, isHDF);
		return isHDF;
	}

	public static List<String> discoverDatasets(final N5Reader n5, final Runnable onInterruption)
	{
		final List<String> datasets = new ArrayList<>();
		final ExecutorService exec = Executors.newFixedThreadPool(
				n5 instanceof N5HDF5Reader ? 1 : 12,
				new NamedThreadFactory("dataset-discovery-%d", true)
		                                                         );
		final AtomicInteger counter = new AtomicInteger(1);
		exec.submit(() -> discoverSubdirectories(n5, "", datasets, exec, counter));
		while (counter.get() > 0 && !Thread.currentThread().isInterrupted())
		{
			try
			{
				Thread.sleep(20);
			} catch (final InterruptedException e)
			{
				exec.shutdownNow();
				onInterruption.run();
			}
		}
		exec.shutdown();
		Collections.sort(datasets);
		return datasets;
	}

	public static void discoverSubdirectories(
			final N5Reader n5,
			final String pathName,
			final Collection<String> datasets,
			final ExecutorService exec,
			final AtomicInteger counter)
	{
		try
		{
			if (isPainteraDataset(n5, pathName))
			{
				synchronized (datasets)
				{
					datasets.add(pathName);
				}
			}
			else if (n5.datasetExists(pathName))
			{
				synchronized (datasets)
				{
					datasets.add(pathName);
				}
			}
			else
			{

				String[] groups = null;
				/* based on attribute */

				boolean isMipmapGroup = Optional.ofNullable(n5.getAttribute(
						pathName,
						MULTI_SCALE_KEY,
						Boolean.class
				                                                           )).orElse(false);

				/* based on groupd content (the old way) */
				if (!isMipmapGroup)
				{
					groups = n5.list(pathName);
					isMipmapGroup = groups.length > 0;
					for (final String group : groups)
					{
						if (!(group.matches("^s[0-9]+$") && n5.datasetExists(pathName + "/" + group)))
						{
							isMipmapGroup = false;
							break;
						}
					}
					if (isMipmapGroup)
					{
						LOG.debug(
								"Found multi-scale group without {} tag. Implicit multi-scale detection will be " +
										"removed in the future. Please add \"{}\":{} to attributes.json.",
								MULTI_SCALE_KEY,
								MULTI_SCALE_KEY,
								true
						        );
					}
				}
				if (isMipmapGroup)
				{
					synchronized (datasets)
					{
						datasets.add(pathName);
					}
				}
				else if (groups != null)
				{
					for (final String group : groups)
					{
						final String groupPathName = pathName + "/" + group;
						final int    numThreads    = counter.incrementAndGet();
						LOG.debug("entering {}, {} threads created", groupPathName, numThreads);
						exec.submit(() -> discoverSubdirectories(n5, groupPathName, datasets, exec, counter));
					}
				}
			}
		} catch (final IOException e)
		{
			LOG.debug(e.toString(), e);
		}
		final int numThreads = counter.decrementAndGet();
		LOG.debug("leaving {}, {} threads remaining", pathName, numThreads);
	}

	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>>
	ImagesWithInvalidate<T, V> openRaw(
			final N5Reader reader,
			final String dataset,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		return openRaw(
				reader,
				dataset,
				getResolution(reader, dataset),
				getOffset(reader, dataset),
				globalCache,
				priority
		              );
	}

	public static <T extends NativeType<T> & RealType<T>, V extends Volatile<T> & NativeType<V> & RealType<V>, A>
	DataSource<T, V> openRawAsSource(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final AxisOrder axisOrder,
			final GlobalCache globalCache,
			final int priority,
			final String name) throws IOException, ReflectionException, AxisOrderNotSupported {
		return openScalarAsSource(
				reader,
				dataset,
				transform,
				axisOrder,
				globalCache,
				priority,
				i -> i == Interpolation.NLINEAR
				     ? new NLinearInterpolatorFactory<>()
				     : new NearestNeighborInterpolatorFactory<>(),
				i -> i == Interpolation.NLINEAR
				     ? new NLinearInterpolatorFactory<>()
				     : new NearestNeighborInterpolatorFactory<>(),
				name
		                         );
	}

	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>, A>
	DataSource<T, V> openScalarAsSource(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final AxisOrder axisOrder,
			final GlobalCache globalCache,
			final int priority,
			final String name) throws IOException, ReflectionException, AxisOrderNotSupported {
		return openScalarAsSource(
				reader,
				dataset,
				transform,
				axisOrder,
				globalCache,
				priority,
				i -> new NearestNeighborInterpolatorFactory<>(),
				i -> new NearestNeighborInterpolatorFactory<>(),
				name
		                         );
	}

	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>, A>
	DataSource<T, V> openScalarAsSource(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final AxisOrder axisOrder,
			final GlobalCache globalCache,
			final int priority,
			final Function<Interpolation, InterpolatorFactory<T, RandomAccessible<T>>> dataInterpolation,
			final Function<Interpolation, InterpolatorFactory<V, RandomAccessible<V>>> interpolation,
			final String name) throws IOException, ReflectionException, AxisOrderNotSupported {

		LOG.debug("Creating N5 Data source from {} {}", reader, dataset);
		return new N5DataSource<>(
				N5Meta.fromReader(reader, dataset),
				transform,
				axisOrder,
				globalCache,
				name,
				priority,
				dataInterpolation,
				interpolation
		);
	}

	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>, A>
	ImagesWithInvalidate<T, V>[] openScalar(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		return isMultiScale(reader, dataset)
		       ? openRawMultiscale(reader, dataset, transform, globalCache, priority)
		       : new ImagesWithInvalidate[] {openRaw(reader, dataset, transform, globalCache, priority)};

	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T extends NativeType<T>, A> Function<NativeImg<T, ? extends A>, T> linkedTypeFactory(final T t)
	{
		return img -> (T) t.getNativeTypeFactory().createLinkedType((NativeImg) img);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>, A>
	ImagesWithInvalidate<T, V> openRaw(
			final N5Reader reader,
			final String dataset,
			final double[] resolution,
			final double[] offset,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		final AffineTransform3D transform = new AffineTransform3D();
		transform.set(
				resolution[0], 0, 0, offset[0],
				0, resolution[1], 0, offset[1],
				0, 0, resolution[2], offset[2]
		             );
		return openRaw(reader, dataset, transform, globalCache, priority);
	}

	@SuppressWarnings("unchecked")
	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>, A extends ArrayDataAccess<A>>
	ImagesWithInvalidate<T, V> openRaw(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final GlobalCache globalCache,
			final int priority) throws IOException {

		try {
			final CellGrid grid = getGrid(reader, dataset);
			final CellLoader<T> loader = new N5CellLoader<>(reader, dataset, reader.getDatasetAttributes(dataset).getBlockSize());
			final T type = N5Helpers.type(reader.getDatasetAttributes(dataset).getDataType());
			final Pair<CachedCellImg<T, A>, Invalidate<Long>> raw = globalCache.createVolatileImg(grid, loader, type);
			final V vtype = (V) VolatileTypeMatcher.getVolatileTypeForType(type);
			final Triple<RandomAccessibleInterval<V>, VolatileCache<Long, Cell<A>>, Invalidate<Long>> vraw = globalCache.wrapAsVolatile(raw.getA(), raw.getB(), priority);
			return new ImagesWithInvalidate<>(raw.getA(), vraw.getA(), transform, raw.getB(), vraw.getC());
		}
		catch (Exception e)
		{
			throw e instanceof IOException ? (IOException) e : new IOException(e);
		}
	}

	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>>
	ImagesWithInvalidate<T, V>[] openRawMultiscale(
			final N5Reader reader,
			final String dataset,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		return openRawMultiscale(
				reader,
				dataset,
				getResolution(reader, dataset),
				getOffset(reader, dataset),
				globalCache,
				priority
		                        );

	}

	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>>
	ImagesWithInvalidate<T, V>[] openRawMultiscale(
			final N5Reader reader,
			final String dataset,
			final double[] resolution,
			final double[] offset,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		final AffineTransform3D transform = new AffineTransform3D();
		transform.set(
				resolution[0], 0, 0, offset[0],
				0, resolution[1], 0, offset[1],
				0, 0, resolution[2], offset[2]
		             );
		return openRawMultiscale(reader, dataset, transform, globalCache, priority);
	}

	@SuppressWarnings("unchecked")
	public static <T extends NativeType<T>, V extends Volatile<T> & NativeType<V>>
	ImagesWithInvalidate<T, V>[] openRawMultiscale(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		final String[] scaleDatasets = N5Helpers.listAndSortScaleDatasets(reader, dataset);

		LOG.debug("Opening directories {} as multi-scale in {}: ", Arrays.toString(scaleDatasets), dataset);

		final double[] initialDonwsamplingFactors = getDownsamplingFactors(
				reader,
				Paths.get(dataset, scaleDatasets[0]).toString()
		                                                                  );
		LOG.debug("Initial transform={}", transform);
		final ExecutorService es = Executors.newFixedThreadPool(
				scaleDatasets.length,
				new NamedThreadFactory("populate-mipmap-scales-%d", true)
		                                                       );
		final ArrayList<Future<Boolean>> futures = new ArrayList<>();
		final ImagesWithInvalidate<T, V>[] imagesWithInvalidate = new ImagesWithInvalidate[scaleDatasets.length];
		for (int scale = 0; scale < scaleDatasets.length; ++scale)
		{
			final int fScale = scale;
			futures.add(es.submit(MakeUnchecked.supplier(() -> {
				LOG.debug("Populating scale level {}", fScale);
				final String scaleDataset = Paths.get(dataset, scaleDatasets[fScale]).toString();
				imagesWithInvalidate[fScale] = openRaw(reader, scaleDataset, transform.copy(), globalCache, priority);
				final double[] downsamplingFactors = getDownsamplingFactors(reader, scaleDataset);
				LOG.debug("Read downsampling factors: {}", Arrays.toString(downsamplingFactors));
				imagesWithInvalidate[fScale].transform.set(considerDownsampling(
						imagesWithInvalidate[fScale].transform.copy(),
						downsamplingFactors,
						initialDonwsamplingFactors));
				LOG.debug("Populated scale level {}", fScale);
				return true;
			})::get));
		}
		futures.forEach(MakeUnchecked.unchecked((CheckedConsumer<Future<Boolean>>) Future::get));
		es.shutdown();
		return imagesWithInvalidate;
	}

	public static DataSource<LabelMultisetType, VolatileLabelMultisetType>
	openLabelMultisetAsSource(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final AxisOrder axisOrder,
			final GlobalCache globalCache,
			final int priority,
			final String name) throws IOException, ReflectionException, AxisOrderNotSupported {
		return new N5DataSource<>(
				N5Meta.fromReader(reader, dataset),
				transform,
				axisOrder,
				globalCache,
				name,
				priority,
				i -> new NearestNeighborInterpolatorFactory<>(),
				i -> new NearestNeighborInterpolatorFactory<>()
		);
	}

	public static ImagesWithInvalidate<LabelMultisetType, VolatileLabelMultisetType> openLabelMutliset(
			final N5Reader reader,
			final String dataset,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		return openLabelMutliset(
				reader,
				dataset,
				getResolution(reader, dataset),
				getOffset(reader, dataset),
				globalCache,
				priority
		                        );
	}

	public static ImagesWithInvalidate<LabelMultisetType, VolatileLabelMultisetType> openLabelMutliset(
			final N5Reader reader,
			final String dataset,
			final double[] resolution,
			final double[] offset,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		final AffineTransform3D transform = new AffineTransform3D();
		transform.set(
				resolution[0], 0, 0, offset[0],
				0, resolution[1], 0, offset[1],
				0, 0, resolution[2], offset[2]
		             );
		return openLabelMutliset(reader, dataset, transform, globalCache, priority);
	}

	public static ImagesWithInvalidate<LabelMultisetType, VolatileLabelMultisetType> openLabelMutliset(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		try {
			final DatasetAttributes attrs = reader.getDatasetAttributes(dataset);
			final N5CacheLoader loader = new N5CacheLoader(
					reader,
					dataset,
					N5CacheLoader.constantNullReplacement(Label.BACKGROUND)
			);
			final Pair<CachedCellImg<LabelMultisetType, VolatileLabelMultisetArray>, Invalidate<Long>> cachedImg = globalCache.createImg(
					new CellGrid(attrs.getDimensions(), attrs.getBlockSize()),
					loader,
					new LabelMultisetType().getEntitiesPerPixel(),
					new VolatileLabelMultisetArray(0, true, new long[]{Label.INVALID})
			);
			cachedImg.getA().setLinkedType(new LabelMultisetType(cachedImg.getA()));

			final Function<NativeImg<VolatileLabelMultisetType, ? extends VolatileLabelMultisetArray>, VolatileLabelMultisetType> linkedTypeFactory =
					img -> new VolatileLabelMultisetType((NativeImg<?, VolatileLabelMultisetArray>) img);

			Triple<RandomAccessibleInterval<VolatileLabelMultisetType>, VolatileCache<Long, Cell<VolatileLabelMultisetArray>>, Invalidate<Long>> vimg = globalCache.wrapAsVolatile(
					cachedImg.getA(),
					cachedImg.getB(),
					linkedTypeFactory,
					new VolatileHelpers.CreateInvalidVolatileLabelMultisetArray(cachedImg.getA().getCellGrid()),
					priority);

			return new ImagesWithInvalidate<>(cachedImg.getA(), vimg.getA(), transform, cachedImg.getB(), vimg.getC());
		}
		catch (InvalidAccessException e)
		{
			throw new IOException(e);
		}

	}

	public static ImagesWithInvalidate<LabelMultisetType, VolatileLabelMultisetType>[] openLabelMultisetMultiscale(
			final N5Reader reader,
			final String dataset,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		return openLabelMultisetMultiscale(
				reader,
				dataset,
				getResolution(reader, dataset),
				getOffset(reader, dataset),
				globalCache,
				priority
		                                  );
	}

	public static ImagesWithInvalidate<LabelMultisetType, VolatileLabelMultisetType>[] openLabelMultisetMultiscale(
			final N5Reader reader,
			final String dataset,
			final double[] resolution,
			final double[] offset,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		final AffineTransform3D transform = new AffineTransform3D();
		transform.set(
				resolution[0], 0, 0, offset[0],
				0, resolution[1], 0, offset[1],
				0, 0, resolution[2], offset[2]
		             );
		return openLabelMultisetMultiscale(
				reader,
				dataset,
				transform,
				globalCache,
				priority
		                                  );
	}

	@SuppressWarnings("unchecked")
	public static ImagesWithInvalidate<LabelMultisetType, VolatileLabelMultisetType>[] openLabelMultisetMultiscale(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final GlobalCache globalCache,
			final int priority) throws IOException
	{
		final String[] scaleDatasets = N5Helpers.listAndSortScaleDatasets(reader, dataset);

		LOG.debug(
				"Opening directories {} as multi-scale in {} and transform={}: ",
				Arrays.toString(scaleDatasets),
				dataset,
				transform
		         );

		final double[] initialDonwsamplingFactors = getDownsamplingFactors(
				reader,
				Paths.get(dataset, scaleDatasets[0]).toString()
		                                                                  );
		final ExecutorService es = Executors.newFixedThreadPool(
				scaleDatasets.length,
				new NamedThreadFactory("populate-mipmap-scales-%d", true)
		                                                       );
		final ArrayList<Future<Boolean>> futures = new ArrayList<>();
		final ImagesWithInvalidate<LabelMultisetType, VolatileLabelMultisetType>[] imagesWithInvalidate = new ImagesWithInvalidate[scaleDatasets.length];
		for (int scale = 0; scale < scaleDatasets.length; ++scale)
		{
			final int fScale = scale;
			futures.add(es.submit(MakeUnchecked.supplier(() -> {
				LOG.debug("Populating scale level {}", fScale);
				final String scaleDataset = Paths.get(dataset, scaleDatasets[fScale]).toString();
				imagesWithInvalidate[fScale] = openLabelMutliset(reader, scaleDataset, transform.copy(), globalCache, priority);
				final double[] downsamplingFactors = getDownsamplingFactors(reader, scaleDataset);
				LOG.debug("Read downsampling factors: {}", Arrays.toString(downsamplingFactors));
				imagesWithInvalidate[fScale].transform.set(considerDownsampling(
						imagesWithInvalidate[fScale].transform.copy(),
						downsamplingFactors,
						initialDonwsamplingFactors));
				LOG.debug("Populated scale level {}", fScale);
				return true;
			})::get));
		}
		futures.forEach(MakeUnchecked.unchecked((CheckedConsumer<Future<Boolean>>) Future::get));
		es.shutdown();
		return imagesWithInvalidate;
	}

	@SuppressWarnings("unchecked")
	public static <D extends NativeType<D>, T extends NativeType<T>> DataSource<D, T>
	openAsLabelSource(
			final N5Reader reader,
			final String dataset,
			final AffineTransform3D transform,
			final AxisOrder axisOrder,
			final GlobalCache globalCache,
			final int priority,
			final String name) throws IOException, ReflectionException, AxisOrderNotSupported {
		return isLabelMultisetType(reader, dataset)
		       ? (DataSource<D, T>) openLabelMultisetAsSource(reader, dataset, transform, axisOrder, globalCache, priority, name)
		       : (DataSource<D, T>) openScalarAsSource(reader, dataset, transform, axisOrder, globalCache, priority, name);
	}

	public static AffineTransform3D considerDownsampling(
			final double[] initialResolution,
			final double[] offset,
			final double[] downsamplingFactors,
			final double[] initialDownsamplingFactors)
	{
		final double[] scaledResolution = new double[downsamplingFactors.length];
		final double[] shift            = new double[downsamplingFactors.length];

		for (int d = 0; d < downsamplingFactors.length; ++d)
		{
			scaledResolution[d] = downsamplingFactors[d] * initialResolution[d];
			shift[d] = 0.5 / initialDownsamplingFactors[d] - 0.5 / downsamplingFactors[d];
		}

		LOG.debug(
				"Downsampling factors={}, scaled resolution={}",
				Arrays.toString(downsamplingFactors),
				Arrays.toString(scaledResolution)
		         );

		final AffineTransform3D transform = new AffineTransform3D();
		transform.set(
				scaledResolution[0], 0, 0, offset[0],
				0, scaledResolution[1], 0, offset[1],
				0, 0, scaledResolution[2], offset[2]
		             );
		return transform.concatenate(new Translation3D(shift));
	}

	public static AffineTransform3D considerDownsampling(
			final AffineTransform3D transform,
			final double[] downsamplingFactors,
			final double[] initialDownsamplingFactors)
	{
		final double[] shift = new double[downsamplingFactors.length];
		for (int d = 0; d < downsamplingFactors.length; ++d)
		{
			transform.set(transform.get(d, d) * downsamplingFactors[d] / initialDownsamplingFactors[d], d, d);
			shift[d] = 0.5 / initialDownsamplingFactors[d] - 0.5 / downsamplingFactors[d];
		}
		return transform.concatenate(new Translation3D(shift));
	}

	public static FragmentSegmentAssignmentState assignments(final N5Writer writer, final String group)
	throws IOException
	{

		if (!isPainteraDataset(writer, group))
		{
			return new FragmentSegmentAssignmentOnlyLocal(
					TLongLongHashMap::new,
					(ks, vs) -> {
						throw new UnableToPersist("Persisting assignments not supported for non Paintera " +
								"group/dataset" +
								" " + group);
					}
			);
		}

		final String dataset = group + "/" + PAINTERA_FRAGMENT_SEGMENT_ASSIGNMENT_DATASTE;

		final Persister persister = (keys, values) -> {
			// TODO how to handle zero length assignments?
			if (keys.length == 0)
			{
				throw new UnableToPersist("Zero length data, will not persist fragment-segment-assignment.");
			}
			try
			{
				final DatasetAttributes attrs = new DatasetAttributes(
						new long[] {keys.length, 2},
						new int[] {keys.length, 1},
						DataType.UINT64,
						new GzipCompression()
				);
				writer.createDataset(dataset, attrs);
				final DataBlock<long[]> keyBlock = new LongArrayDataBlock(
						new int[] {keys.length, 1},
						new long[] {0, 0},
						keys
				);
				final DataBlock<long[]> valueBlock = new LongArrayDataBlock(
						new int[] {values.length, 1},
						new long[] {0, 1},
						values
				);
				writer.writeBlock(dataset, attrs, keyBlock);
				writer.writeBlock(dataset, attrs, valueBlock);
			} catch (final Exception e)
			{
				throw new UnableToPersist(e);
			}
		};

		final Supplier<TLongLongMap> initialLutSupplier = MakeUnchecked.supplier(() -> {
			final long[] keys;
			final long[] values;
			LOG.debug("Found fragment segment assingment dataset {}? {}", dataset, writer.datasetExists(dataset));
			if (writer.datasetExists(dataset))
			{
				final DatasetAttributes attrs      = writer.getDatasetAttributes(dataset);
				final int               numEntries = (int) attrs.getDimensions()[0];
				keys = new long[numEntries];
				values = new long[numEntries];
				LOG.debug("Found {} assignments", numEntries);
				final RandomAccessibleInterval<UnsignedLongType> data = N5Utils.open(writer, dataset);

				final Cursor<UnsignedLongType> keysCursor = Views.flatIterable(Views.hyperSlice(data, 1, 0l)).cursor();
				for (int i = 0; keysCursor.hasNext(); ++i)
				{
					keys[i] = keysCursor.next().get();
				}

				final Cursor<UnsignedLongType> valuesCursor = Views.flatIterable(Views.hyperSlice(
						data,
						1,
						1l
				                                                                                 )).cursor();
				for (int i = 0; valuesCursor.hasNext(); ++i)
				{
					values[i] = valuesCursor.next().get();
				}
			}
			else
			{
				keys = new long[] {};
				values = new long[] {};
			}
			return new TLongLongHashMap(keys, values);
		});

		return new FragmentSegmentAssignmentOnlyLocal(initialLutSupplier, persister);
	}

	public static IdService idService(final N5Writer n5, final String dataset) throws IOException
	{

		LOG.debug("Requesting id service for {}:{}", n5, dataset);
		final Long maxId = n5.getAttribute(dataset, "maxId", Long.class);
		LOG.debug("Found maxId={}", maxId);
		if (maxId == null) { throw new IOException("maxId not specified in attributes.json"); }
		return new N5IdService(n5, dataset, maxId);

	}

	public static String getFinestLevel(
			final N5Reader n5,
			final String dataset) throws IOException
	{
		LOG.debug("Getting finest level for dataset {}", dataset);
		final String[] scaleDirs = listAndSortScaleDatasets(n5, dataset);
		return Paths.get(dataset, scaleDirs[0]).toString();
	}

	public static String getCoarsestLevel(
			final N5Reader n5,
			final String dataset) throws IOException
	{
		final String[] scaleDirs = listAndSortScaleDatasets(n5, dataset);
		return Paths.get(dataset, scaleDirs[scaleDirs.length - 1]).toString();
	}

	public static double[] getDoubleArrayAttribute(
			final N5Reader n5,
			final String dataset,
			final String key,
			final double... fallBack) throws IOException
	{
		return getDoubleArrayAttribute(n5, dataset, key, false, fallBack);
	}

	public static double[] getDoubleArrayAttribute(
			final N5Reader n5,
			final String dataset,
			final String key,
			final boolean revert,
			final double... fallBack)
	throws IOException
	{

		if (revert)
		{
			final double[] toRevert = getDoubleArrayAttribute(n5, dataset, key, false, fallBack);
			LOG.debug("Will revert {}", toRevert);
			for ( int i = 0, k = toRevert.length - 1; i < toRevert.length / 2; ++i, --k)
			{
				double tmp = toRevert[i];
				toRevert[i] = toRevert[k];
				toRevert[k] = tmp;
			}
			LOG.debug("Reverted {}", toRevert);
			return toRevert;
		}

		if (isPainteraDataset(n5, dataset))
		{
			return getDoubleArrayAttribute(n5, dataset + "/" + PAINTERA_DATA_DATASET, key, revert, fallBack);
		}
		try {
			return Optional.ofNullable(n5.getAttribute(dataset, key, double[].class)).orElse(fallBack);
		}
		catch (ClassCastException e)
		{
			LOG.debug("Caught exception when trying to read double[] attribute. Will try to read as long[] attribute instead.", e);
			return Optional.ofNullable(asDoubleArray(n5.getAttribute(dataset, key, long[].class))).orElse(fallBack);
		}
	}


	public static double[] getResolution(final N5Reader n5, final String dataset) throws IOException
	{
		return getResolution(n5, dataset, false);
	}

	public static double[] getResolution(final N5Reader n5, final String dataset, boolean revert) throws IOException
	{
		return getDoubleArrayAttribute(n5, dataset, RESOLUTION_KEY, revert, 1.0, 1.0, 1.0);
	}

	public static double[] getOffset(final N5Reader n5, final String dataset) throws IOException
	{
		return getOffset(n5, dataset, false);
	}

	public static double[] getOffset(final N5Reader n5, final String dataset, boolean revert) throws IOException
	{
		return getDoubleArrayAttribute(n5, dataset, OFFSET_KEY, revert,0.0, 0.0, 0.0);
	}

	public static double[] getDownsamplingFactors(final N5Reader n5, final String dataset) throws IOException
	{
		return getDoubleArrayAttribute(n5, dataset, DOWNSAMPLING_FACTORS_KEY, 1.0, 1.0, 1.0);
	}

	public static AffineTransform3D fromResolutionAndOffset(final double[] resolution, final double[] offset)
	{
		return new AffineTransform3D().concatenate(new ScaleAndTranslation(resolution, offset));
	}

	public static AffineTransform3D getTransform(final N5Reader n5, final String dataset) throws IOException
	{
		return getTransform(n5, dataset, false);
	}

	public static AffineTransform3D getTransform(final N5Reader n5, final String dataset, boolean revertSpatialAttributes) throws IOException
	{
		return fromResolutionAndOffset(getResolution(n5, dataset, revertSpatialAttributes), getOffset(n5, dataset, revertSpatialAttributes));
	}

	public static <A, B, C> ValueTriple<A[], B[], C[]> asArrayTriple(final ValueTriple<A, B, C> scalarTriple)
	{
		final A a = scalarTriple.getA();
		final B b = scalarTriple.getB();
		final C c = scalarTriple.getC();

		@SuppressWarnings("unchecked") final A[] aArray = (A[]) Array.newInstance(a.getClass(), 1);
		@SuppressWarnings("unchecked") final B[] bArray = (B[]) Array.newInstance(b.getClass(), 1);
		@SuppressWarnings("unchecked") final C[] cArray = (C[]) Array.newInstance(c.getClass(), 1);

		aArray[0] = a;
		bArray[0] = b;
		cArray[0] = c;

		return new ValueTriple<>(aArray, bArray, cArray);
	}

	public static String lastSegmentOfDatasetPath(final String dataset)
	{
		return Paths.get(dataset).getFileName().toString();
	}

	public static String labelMappingFromFileBasePath(final N5Reader reader, final String dataset)
			throws IOException, ReflectionException
	{
		if(!isPainteraDataset(reader, dataset))
		{
			return null;
		}

		final N5FSMeta meta     = new N5FSMeta((N5FSReader) reader, dataset);
		final String   basePath = Paths.get(
				meta.basePath(),
				dataset,
				LABEL_TO_BLOCK_MAPPING
		).toAbsolutePath().toString();

		return basePath;
	}

	public static String[] labelMappingFromFileLoaderPattern(final N5Reader reader, final String dataset)
	throws IOException, ReflectionException
	{
		if (!isPainteraDataset(reader, dataset))
		{
			if (isMultiScale(reader, dataset))
			{
				return Stream.generate(() -> null).limit(N5Helpers.listScaleDatasets(reader, dataset).length).toArray(
						String[]::new);
			}
			else
			{
				return new String[] {null};
			}
		}

		if (!(reader instanceof N5FSReader))
		{
			if (isMultiScale(reader, dataset + "/data"))
			{
				final String[] sortedScaleDirs = listAndSortScaleDatasets(reader, dataset + "/data");
				return Arrays
						.stream(sortedScaleDirs)
						.map(ssd -> (String) null)
						.toArray(String[]::new);
			}
			else
			{
				return new String[] {null};
			}
		}

		final N5FSMeta meta = new N5FSMeta((N5FSReader) reader, dataset);
		final String basePath = Paths.get(
				meta.basePath(),
				dataset,
				LABEL_TO_BLOCK_MAPPING
		                                 ).toAbsolutePath().toString();
		if (isMultiScale(reader, dataset + "/data"))
		{
			final String[] sortedScaleDirs = listAndSortScaleDatasets(reader, dataset + "/data");
			return Arrays
					.stream(sortedScaleDirs)
					.map(ssd -> Paths.get(basePath, ssd, "%d"))
					.map(Path::toAbsolutePath)
					.map(Path::toString)
					.toArray(String[]::new);
		}
		else
		{
			return new String[] {basePath + "/%d"};
		}

	}

	public static LabelBlockLookup getLabelBlockLookup(N5Reader reader, String group) throws IOException
	{
		try {
			LOG.debug("Getting label block lookup for {}", N5Meta.fromReader(reader, group));
			if (reader instanceof N5FSReader && isPainteraDataset(reader, group)) {
				N5FSMeta n5fs = new N5FSMeta((N5FSReader) reader, group);
				final GsonBuilder gsonBuilder = new GsonBuilder().registerTypeHierarchyAdapter(LabelBlockLookup.class, LabelBlockLookupAdapter.getJsonAdapter());
				final Gson gson = gsonBuilder.create();
				final N5Reader appropriateReader = n5fs.reader(gsonBuilder);
				final JsonElement labelBlockLookupJson = reader.getAttribute(group, "labelBlockLookup", JsonElement.class);
				LOG.debug("Got label block lookup json: {}", labelBlockLookupJson);
				final LabelBlockLookup lookup = Optional
						.ofNullable(labelBlockLookupJson)
						.filter(JsonElement::isJsonObject)
						.map(obj -> gson.fromJson(obj, LabelBlockLookup.class))
						.orElseGet(MakeUnchecked.supplier(() -> new LabelBlockLookupFromFile(Paths.get(n5fs.basePath(), group, "/", "label-to-block-mapping", "s%d", "%d").toString())));
				LOG.debug("Got lookup type: {}", lookup.getClass());
				return lookup;
			} else
				return new LabelBlockLookupNotSupportedForNonPainteraDataset();
		}
		catch (final ReflectionException e)
		{
			throw new IOException(e);
		}
	}

	@LabelBlockLookup.LookupType("UNSUPPORTED")
	private static class LabelBlockLookupNotSupportedForNonPainteraDataset implements LabelBlockLookup
	{

		private LabelBlockLookupNotSupportedForNonPainteraDataset()
		{
			LOG.info("3D meshes not supported for non Paintera dataset!");
		}

		@Override
		public Interval[] read( int level, long id )
		{
			LOG.debug("Reading blocks not supported for non-paintera dataset -- returning empty array");
			return new Interval[ 0 ];
		}

		@Override
		public void write( int level, long id, Interval... intervals )
		{
			LOG.debug("Saving blocks not supported for non-paintera dataset");
		}

		// This is here because annotation interfaces cannot have members in kotlin (currently)
		// https://stackoverflow.com/questions/49661899/java-annotation-implementation-to-kotlin
		@Override
		public String getType()
		{
			return "UNSUPPORTED";
		}
	}

	public static void createEmptyLabeLDataset(
			String container,
			String group,
			long[] dimensions,
			int[] blockSize,
			double[] resolution,
			double[] offset,
			double[][] relativeScaleFactors,
			int[] maxNumEntries
	                                          ) throws IOException
	{
		createEmptyLabeLDataset(container, group, dimensions, blockSize, resolution, offset,relativeScaleFactors, maxNumEntries, false);
	}

	public static void createEmptyLabeLDataset(
			String container,
			String group,
			long[] dimensions,
			int[] blockSize,
			double[] resolution,
			double[] offset,
			double[][] relativeScaleFactors,
			int[] maxNumEntries,
			boolean ignoreExisiting
	                                          ) throws IOException
	{

		//		{"painteraData":{"type":"label"},
		// "maxId":191985,
		// "labelBlockLookup":{"attributes":{},"root":"/home/phil/local/tmp/sample_a_padded_20160501.n5",
		// "scaleDatasetPattern":"volumes/labels/neuron_ids/oke-test/s%d","type":"n5-filesystem"}}

		final Map<String, String> pd = new HashMap<String, String>();
		pd.put("type", "label");
		final N5FSWriter n5 = new N5FSWriter(container);
		final String uniqueLabelsGroup = String.format("%s/unique-labels", group);

		if (!ignoreExisiting && n5.datasetExists(group))
			throw new IOException(String.format("Dataset `%s' already exists in container `%s'", group, container));

		if (!n5.exists(group))
			n5.createGroup(group);

		if (!ignoreExisiting && n5.listAttributes(group).containsKey(PAINTERA_DATA_KEY))
			throw new IOException(String.format("Group `%s' exists in container `%s' and is Paintera data set", group, container));

		if (!ignoreExisiting && n5.exists(uniqueLabelsGroup))
			throw new IOException(String.format("Unique labels group `%s' exists in container `%s' -- conflict likely.", uniqueLabelsGroup, container));

		n5.setAttribute(group, PAINTERA_DATA_KEY, pd);
		n5.setAttribute(group, MAX_ID_KEY, 1L);

		final String dataGroup = String.format("%s/data", group);
		n5.createGroup(dataGroup);

		// {"maxId":191978,"multiScale":true,"offset":[3644.0,3644.0,1520.0],"resolution":[4.0,4.0,40.0],
		// "isLabelMultiset":true}%
		n5.setAttribute(dataGroup, MULTI_SCALE_KEY, true);
		n5.setAttribute(dataGroup, OFFSET_KEY, offset);
		n5.setAttribute(dataGroup, RESOLUTION_KEY, resolution);
		n5.setAttribute(dataGroup, IS_LABEL_MULTISET_KEY, true);

		n5.createGroup(uniqueLabelsGroup);
		n5.setAttribute(uniqueLabelsGroup, MULTI_SCALE_KEY, true);

		final String scaleDatasetPattern      = String.format("%s/s%%d", dataGroup);
		final String scaleUniqueLabelsPattern = String.format("%s/s%%d", uniqueLabelsGroup);
		final long[]       scaledDimensions         = dimensions.clone();
		final double[] accumulatedFactors = new double[] {1.0, 1.0, 1.0};
		for (int scaleLevel = 0, downscaledLevel = -1; downscaledLevel < relativeScaleFactors.length; ++scaleLevel, ++downscaledLevel)
		{
			double[]     scaleFactors       = downscaledLevel < 0 ? null : relativeScaleFactors[downscaledLevel];

			final String dataset            = String.format(scaleDatasetPattern, scaleLevel);
			final String uniqeLabelsDataset = String.format(scaleUniqueLabelsPattern, scaleLevel);
			final int maxNum = downscaledLevel < 0 ? -1 : maxNumEntries[downscaledLevel];
			n5.createDataset(dataset, scaledDimensions, blockSize, DataType.UINT8, new GzipCompression());
			n5.createDataset(uniqeLabelsDataset, scaledDimensions, blockSize, DataType.UINT64, new GzipCompression());

			// {"maxNumEntries":-1,"compression":{"type":"gzip","level":-1},"downsamplingFactors":[2.0,2.0,1.0],"blockSize":[64,64,64],"dataType":"uint8","dimensions":[625,625,125]}%
			n5.setAttribute(dataset, MAX_NUM_ENTRIES_KEY, maxNum);
			if (scaleLevel == 0)
				n5.setAttribute(dataset, IS_LABEL_MULTISET_KEY, true);
			else
			{
				n5.setAttribute(dataset, DOWNSAMPLING_FACTORS_KEY, accumulatedFactors);
				n5.setAttribute(uniqeLabelsDataset, DOWNSAMPLING_FACTORS_KEY, accumulatedFactors);
			}



			// {"compression":{"type":"gzip","level":-1},"downsamplingFactors":[2.0,2.0,1.0],"blockSize":[64,64,64],"dataType":"uint64","dimensions":[625,625,125]}

			if (scaleFactors != null)
			{
				Arrays.setAll(scaledDimensions, dim -> (long) Math.ceil(scaledDimensions[dim] / scaleFactors[dim]));
				Arrays.setAll(accumulatedFactors, dim -> accumulatedFactors[dim] * scaleFactors[dim]);
			}
		}
	}

	public static CellGrid getGrid(N5Reader reader, final String dataset) throws IOException {
		DatasetAttributes attributes = reader.getDatasetAttributes(dataset);
		return new CellGrid(attributes.getDimensions(), attributes.getBlockSize());
	}

	public static final <T extends NativeType<T>> T type(final DataType dataType) {
		switch (dataType) {
			case INT8:
				return (T) new ByteType();
			case UINT8:
				return (T) new UnsignedByteType();
			case INT16:
				return (T) new ShortType();
			case UINT16:
				return (T) new UnsignedShortType();
			case INT32:
				return (T) new IntType();
			case UINT32:
				return (T) new UnsignedIntType();
			case INT64:
				return (T) new LongType();
			case UINT64:
				return (T) new UnsignedLongType();
			case FLOAT32:
				return (T) new FloatType();
			case FLOAT64:
				return (T) new DoubleType();
			default:
				return null;
		}
	}

	public static CellGrid asCellGrid(DatasetAttributes attributes)
	{
		return new CellGrid(attributes.getDimensions(), attributes.getBlockSize());
	}

	public static String volumetricDataGroup(String group, boolean isPainteraDataset)
	{
		return isPainteraDataset
				? group + "/" + N5Helpers.PAINTERA_DATA_DATASET
				: group;
	}

	private static double[] asDoubleArray(long[] array)
	{
		final double[] doubleArray = new double[array.length];
		Arrays.setAll(doubleArray, d -> array[d]);
		return doubleArray;
	}
}
