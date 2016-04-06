package bdv.bigcat.ui;

import bdv.AbstractViewerSetupImgLoader;
import bdv.labels.labelset.LabelMultisetType;
import bdv.labels.labelset.VolatileLabelMultisetType;
import bdv.labels.labelset.VolatileLabelMultisetARGBConverter;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.volatiles.VolatileARGBType;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;

@SuppressWarnings( "unchecked" )
public class ARGBConvertedLabelsSource
	implements Source< VolatileARGBType >
{
	final private AbstractViewerSetupImgLoader< LabelMultisetType, VolatileLabelMultisetType > multisetImageLoader;
	final private ARGBStream argbSource;
	final private long setupId;

	final protected InterpolatorFactory< VolatileARGBType, RandomAccessible< VolatileARGBType > >[] interpolatorFactories;
	{
		interpolatorFactories = new InterpolatorFactory[]{
				new NearestNeighborInterpolatorFactory< VolatileARGBType >(),
				new ClampingNLinearInterpolatorFactory< VolatileARGBType >()
		};
	}

	public ARGBConvertedLabelsSource(
			final int setupId,
			final AbstractViewerSetupImgLoader< LabelMultisetType, VolatileLabelMultisetType > multisetImageLoader,
			final ARGBStream argbSource )
	{
		this.setupId = setupId;
		this.multisetImageLoader = multisetImageLoader;
		this.argbSource = argbSource;
	}

	final public AbstractViewerSetupImgLoader< LabelMultisetType, VolatileLabelMultisetType > getLoader()
	{
		return multisetImageLoader;
	}

	@Override
	public boolean isPresent( final int t )
	{
		return true;
	}

	@Override
	public RandomAccessibleInterval< VolatileARGBType > getSource( final int t, final int level )
	{
		return Converters.convert(
				multisetImageLoader.getVolatileImage( t, level ),
				new VolatileLabelMultisetARGBConverter( argbSource ),
				new VolatileARGBType() );
	}

	@Override
	public RealRandomAccessible< VolatileARGBType > getInterpolatedSource( final int t, final int level, final Interpolation method )
	{
		final ExtendedRandomAccessibleInterval< VolatileARGBType, RandomAccessibleInterval< VolatileARGBType > > extendedSource =
				Views.extendValue( getSource( t,  level ), new VolatileARGBType( 0 ) );
		switch ( method )
		{
		case NLINEAR :
			return Views.interpolate( extendedSource, interpolatorFactories[ 1 ] );
		default :
			return Views.interpolate( extendedSource, interpolatorFactories[ 0 ] );
		}
	}

	@Override
	public void getSourceTransform( final int t, final int level, final AffineTransform3D transform )
	{
		transform.set( multisetImageLoader.getMipmapTransforms()[ level ] );
	}

	@Override
	public AffineTransform3D getSourceTransform( final int t, final int level )
	{
		return multisetImageLoader.getMipmapTransforms()[ level ];
	}

	@Override
	public VolatileARGBType getType()
	{
		return new VolatileARGBType();
	}

	/**
	 * TODO Have a name and return it.
	 */
	@Override
	public String getName()
	{
		return setupId + "";
	}

	/**
	 * TODO Have VoxelDimensions and return it.
	 */
	@Override
	public VoxelDimensions getVoxelDimensions()
	{
		return null;
	}

	/**
	 * TODO Store this in a field
	 */
	@Override
	public int getNumMipmapLevels()
	{
		return multisetImageLoader.getMipmapResolutions().length;
	}

	// TODO: make ARGBType version of this source
	public Source nonVolatile()
	{
		return this;
	}
}
