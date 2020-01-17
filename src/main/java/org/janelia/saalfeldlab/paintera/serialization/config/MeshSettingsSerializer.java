package org.janelia.saalfeldlab.paintera.serialization.config;

import java.lang.reflect.Type;
import java.util.Optional;

import org.janelia.saalfeldlab.paintera.meshes.MeshSettings;
import org.janelia.saalfeldlab.paintera.serialization.PainteraSerialization;
import org.scijava.plugin.Plugin;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;

@Plugin(type = PainteraSerialization.PainteraAdapter.class)
public class MeshSettingsSerializer implements PainteraSerialization.PainteraAdapter<MeshSettings> {

	private static final String NUM_SCALE_LEVELS_KEY = "numScaleLevels";

	private static final String LEVEL_OF_DETAIL_KEY = "levelOfDetail";

	private static final String COARSEST_SCALE_LEVEL_KEY = "coarsestScaleLevel";

	private static final String FINEST_SCALE_LEVEL_KEY = "finestScaleLevel";

	private static final String SIMPLIFICATION_ITERATIONS_KEY = "simplificationIterations";

	private static final String SMOOTHING_LAMBDA_KEY = "smoothingLambda";

	private static final String SMOOTHING_ITERATIONS_KEY = "smoothingIterations";

	private static final String MIN_LABEL_RATIO_KEY = "minLabelRatio";

	private static final String OPACITY_KEY = "opacity";

	private static final String DRAW_MODE_KEY = "drawMode";

	private static final String CULL_FACE_KEY = "cullFace";

	private static final String INFLATE_KEY = "inflate";

	private static final String IS_VISIBLE_KEY = "isVisible";

	@Override
	public MeshSettings deserialize(
			final JsonElement json,
			final Type typeOfT,
			final JsonDeserializationContext context)
			throws JsonParseException {
		final JsonObject map = json.getAsJsonObject();
		final MeshSettings settings = new MeshSettings(map.get(NUM_SCALE_LEVELS_KEY).getAsInt());
		Optional.ofNullable(map.get(LEVEL_OF_DETAIL_KEY)).map(JsonElement::getAsInt).ifPresent(settings.levelOfDetailProperty()::set);
		Optional.ofNullable(map.get(COARSEST_SCALE_LEVEL_KEY)).map(JsonElement::getAsInt).ifPresent(settings.coarsestScaleLevelProperty()::set);
		Optional.ofNullable(map.get(FINEST_SCALE_LEVEL_KEY)).map(JsonElement::getAsInt).ifPresent(settings.finestScaleLevelProperty()::set);
		Optional.ofNullable(map.get(SIMPLIFICATION_ITERATIONS_KEY)).map(JsonElement::getAsInt).ifPresent(settings.simplificationIterationsProperty()::set);
		Optional.ofNullable(map.get(SMOOTHING_ITERATIONS_KEY)).map(JsonElement::getAsInt).ifPresent(settings.smoothingIterationsProperty()::set);
		Optional.ofNullable(map.get(SMOOTHING_LAMBDA_KEY)).map(JsonElement::getAsDouble).ifPresent(settings.smoothingLambdaProperty()::set);
		Optional.ofNullable(map.get(MIN_LABEL_RATIO_KEY)).map(JsonElement::getAsDouble).ifPresent(settings.minLabelRatioProperty()::set);
		Optional.ofNullable(map.get(OPACITY_KEY)).map(JsonElement::getAsDouble).ifPresent(settings.opacityProperty()::set);
		Optional.ofNullable(map.get(INFLATE_KEY)).map(JsonElement::getAsDouble).ifPresent(settings.inflateProperty()::set);
		Optional.ofNullable(map.get(DRAW_MODE_KEY)).map(el -> (DrawMode) context.deserialize(el, DrawMode.class)).ifPresent(settings.drawModeProperty()::set);
		Optional.ofNullable(map.get(CULL_FACE_KEY)).map(el -> (CullFace) context.deserialize(el, CullFace.class)).ifPresent(settings.cullFaceProperty()::set);
		Optional.ofNullable(map.get(IS_VISIBLE_KEY)).map(JsonElement::getAsBoolean).ifPresent(settings.isVisibleProperty()::set);
		return settings;
	}

	@Override
	public JsonElement serialize(final MeshSettings src, final Type typeOfSrc, final JsonSerializationContext context) {
		final JsonObject map = new JsonObject();
		map.addProperty(NUM_SCALE_LEVELS_KEY, src.numScaleLevels());

		if (MeshSettings.DEFAULT_LEVEL_OF_DETAIL != src.levelOfDetailProperty().get())
			map.addProperty(LEVEL_OF_DETAIL_KEY, src.levelOfDetailProperty().get());

		if (MeshSettings.getDefaultCoarsestScaleLevel(src.numScaleLevels()) != src.coarsestScaleLevelProperty().get())
			map.addProperty(COARSEST_SCALE_LEVEL_KEY, src.coarsestScaleLevelProperty().get());

		if (MeshSettings.getDefaultFinestScaleLevel(src.numScaleLevels()) != src.finestScaleLevelProperty().get())
			map.addProperty(FINEST_SCALE_LEVEL_KEY, src.finestScaleLevelProperty().get());

		if (MeshSettings.DEFAULT_MESH_SIMPLIFICATION_ITERATIONS != src.simplificationIterationsProperty().get())
			map.addProperty(SIMPLIFICATION_ITERATIONS_KEY, src.simplificationIterationsProperty().get());

		if (MeshSettings.DEFAULT_MESH_SMOOTHING_LAMBDA != src.smoothingLambdaProperty().get())
			map.addProperty(SMOOTHING_LAMBDA_KEY, src.smoothingLambdaProperty().get());

		if (MeshSettings.DEFAULT_MESH_SMOOTHING_ITERATIONS != src.smoothingIterationsProperty().get())
			map.addProperty(SMOOTHING_ITERATIONS_KEY, src.smoothingIterationsProperty().get());

		if (MeshSettings.DEFAULT_MIN_LABEL_RATIO != src.minLabelRatioProperty().get())
			map.addProperty(MIN_LABEL_RATIO_KEY, src.minLabelRatioProperty().get());

		if (MeshSettings.DEFAULT_MESH_OPACITY != src.opacityProperty().get())
			map.addProperty(OPACITY_KEY, src.opacityProperty().get());

		if (MeshSettings.DEFAULT_MESH_INFLATE != src.inflateProperty().get())
			map.addProperty(INFLATE_KEY, src.inflateProperty().get());

		if (MeshSettings.DEFAULT_MESH_IS_VISIBLE!= src.isVisibleProperty().get())
			map.addProperty(IS_VISIBLE_KEY, src.isVisibleProperty().get());

		if (MeshSettings.DEFAULT_MESH_DRAWMODE != src.drawModeProperty().get())
			map.add(DRAW_MODE_KEY, context.serialize(src.drawModeProperty().get()));

		if (MeshSettings.DEFAULT_MESH_CULLFACE != src.cullFaceProperty().get())
			map.add(CULL_FACE_KEY, context.serialize(src.cullFaceProperty().get()));

		return map;
	}

	@Override
	public Class<MeshSettings> getTargetClass() {
		return MeshSettings.class;
	}
}
