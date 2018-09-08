package org.janelia.saalfeldlab.paintera.ui.opendialog.meta;

import java.util.Arrays;
import java.util.HashSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableObjectValue;
import javafx.beans.value.ObservableStringValue;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.TextFormatter.Change;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import org.janelia.saalfeldlab.fx.Buttons;
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread;
import org.janelia.saalfeldlab.paintera.data.axisorder.AxisOrder;

public class MetaPanel
{

	private static final double GRID_HGAP = 0;

	private static final double TEXTFIELD_WIDTH = 100;

	private static final String X_STRING = "X";

	private static final String Y_STRING = "Y";

	private static final String Z_STRING = "Z";

	private final SpatialInformation resolution;

	private final SpatialInformation offset;

	private final TextField min = new TextField("");

	private final TextField max = new TextField("");

	private final VBox content = new VBox();

	private final ScrollPane cc = new ScrollPane(content);

	private final TitledPane pane = new TitledPane("meta", cc);

	private final VBox rawMeta = new VBox();

	private final VBox labelMeta = new VBox();

	private final HashSet<Node> additionalMeta = new HashSet<>();

	private final SimpleObjectProperty<TYPE> dataType = new SimpleObjectProperty<>(null);

	private final SimpleObjectProperty<long[]> dimensionsProperty = new SimpleObjectProperty<>(null);

	private final SimpleObjectProperty<AxisOrder> axisOrder = new SimpleObjectProperty<>(null);

	private final ObservableList<AxisOrder> axisOrderChoices = FXCollections.observableArrayList();

	private final Button revertButton = Buttons.withTooltip("Revert", "Revert array attributes", e -> {});


	{
		dimensionsProperty.addListener((obs, oldv, newv) -> {
			if (newv != null ) {
			}
		});
	}

	public MetaPanel()
	{
		this.resolution = new SpatialInformation(
			TEXTFIELD_WIDTH,
			X_STRING,
			Y_STRING,
			Z_STRING,
			v -> v > 0,
			SpatialInformation.Submit.ON_ENTER,
			SpatialInformation.Submit.ON_FOCUS_LOST);
		this.resolution.textX().setText("1.0");
		this.resolution.textY().setText("1.0");
		this.resolution.textZ().setText("1.0");

		this.offset = new SpatialInformation(
			TEXTFIELD_WIDTH,
			X_STRING,
			Y_STRING,
			Z_STRING,
			v -> true,
			SpatialInformation.Submit.ON_ENTER,
			SpatialInformation.Submit.ON_FOCUS_LOST);

		cc.setFitToWidth(true);

		final GridPane spatialInfo = new GridPane();
		spatialInfo.setHgap(GRID_HGAP);
		final Label empty = new Label("");
		final Label xLabel = new Label(X_STRING);
		final Label yLabel = new Label(Y_STRING);
		final Label zLabel = new Label(Z_STRING);

		formatLabels(empty, xLabel, yLabel, zLabel);
		addToGrid(spatialInfo, 0, 0, empty, xLabel, yLabel, zLabel);
		addToGrid(
				spatialInfo,
				0,
				1,
				new Label("Resolution "),
				resolution.textX(),
				resolution.textY(),
				resolution.textZ()
		         );
		addToGrid(spatialInfo, 0, 2, new Label("Offset"), offset.textX(), offset.textY(), offset.textZ());
		spatialInfo.add(revertButton, 3, 3);
		revertButton.setPrefWidth(TEXTFIELD_WIDTH);
		final ColumnConstraints cc = new ColumnConstraints();
		cc.setHgrow(Priority.ALWAYS);
		spatialInfo.getColumnConstraints().addAll(cc);

		StackPane dimensionInfo = new StackPane();
		// max num of labels

		this.dimensionsProperty.addListener((obs, oldv, newv) -> {
			if (newv == null)
				InvokeOnJavaFXApplicationThread.invoke(dimensionInfo.getChildren()::clear);
			else
			{
				final AxisOrder[] supportedAxes = Arrays
						.stream(AxisOrder.valuesFor(newv.length))
						.filter(order -> AxisOrder.XYZ.equals(order.spatialOnly()))
						.toArray(AxisOrder[]::new);
				this.axisOrderChoices.setAll(supportedAxes);
				this.axisOrder.set(AxisOrder.defaultOrder(newv.length).get());
				ComboBox<AxisOrder> axisOrderComboBox = new ComboBox<>(this.axisOrderChoices);
				axisOrderComboBox.valueProperty().bindBidirectional(this.axisOrder);
				Label[] labels = Stream.generate(Label::new).limit(newv.length).toArray(Label[]::new);
				Stream.of(labels).forEach(l -> l.setTextAlignment(TextAlignment.CENTER));
				Stream.of(labels).forEach(l -> l.setAlignment(Pos.CENTER));
				Stream.of(labels).forEach(l -> l.setPrefWidth(TEXTFIELD_WIDTH));
				this.axisOrder.addListener((obsAx, oldvAx, newvAx) -> {
					if (newvAx == null)
						return;
					AxisOrder.Axis[] axes = newvAx.axes();
					for (int i = 0; i < labels.length; ++i)
						labels[i].setText(axes[i].name());
				});
				AxisOrder.Axis[] axes = this.axisOrder.get().axes();
				for (int i = 0; i < labels.length; ++i)
					labels[i].setText(axes[i].name());
				GridPane grid = new GridPane();
				for (int d = 0; d < newv.length; ++d)
				{
					final TextField lbl = new TextField("" + newv[d]);
					lbl.setEditable(false);
					grid.add(labels[d], d + 1, 0);
					grid.add(lbl, d + 1, 1);
					lbl.setPrefWidth(TEXTFIELD_WIDTH);
				}
				final Label axisOrderLabel = new Label("Axis Order");
				grid.add(axisOrderLabel, 0, 0);
				grid.add(axisOrderComboBox, 0, 1);
				GridPane.setHgrow(axisOrderLabel, Priority.ALWAYS);
				GridPane.setHgrow(axisOrderComboBox, Priority.ALWAYS);
				InvokeOnJavaFXApplicationThread.invoke(() -> dimensionInfo.getChildren().add(grid));
			}
		});

		content.getChildren().addAll(spatialInfo, new Separator(Orientation.HORIZONTAL), dimensionInfo, new Separator(Orientation.HORIZONTAL));

		this.dataType.addListener((obs, oldv, newv) -> {
			if (newv != null)
				InvokeOnJavaFXApplicationThread.invoke(() -> {
					final ObservableList<Node> children = this.content.getChildren();
					children.removeAll(this.additionalMeta);
					this.additionalMeta.clear();
					switch (newv)
					{
						case RAW:
							children.add(this.rawMeta);
							this.additionalMeta.add(this.rawMeta);
							break;
						case LABEL:
							children.add(this.labelMeta);
							this.additionalMeta.add(this.labelMeta);
							break;
						default:
							break;
					}
				});
		});

		final GridPane rawMinMax = new GridPane();
		rawMinMax.getColumnConstraints().add(cc);
		rawMinMax.add(new Label("Intensity Range"), 0, 0);
		rawMinMax.add(this.min, 1, 0);
		rawMinMax.add(this.max, 2, 0);
		this.min.setPromptText("min");
		this.max.setPromptText("max");
		this.min.setPrefWidth(TEXTFIELD_WIDTH);
		this.max.setPrefWidth(TEXTFIELD_WIDTH);
		this.rawMeta.getChildren().add(rawMinMax);

	}

	public void listenOnResolution(final DoubleProperty x, final DoubleProperty y, final DoubleProperty z)
	{
		this.resolution.bindTo(x, y, z);
	}

	public void listenOnOffset(final DoubleProperty x, final DoubleProperty y, final DoubleProperty z)
	{
		this.offset.bindTo(x, y, z);
	}

	public void listenOnDimensions(final ObservableObjectValue<long[]> dimensions)
	{
		this.dimensionsProperty.bind(dimensions);
	}

	public void listenOnMinMax(final DoubleProperty min, final DoubleProperty max)
	{
		min.addListener((obs, oldv, newv) -> {
			if (Double.isFinite(newv.doubleValue()))
				this.min.setText(Double.toString(newv.doubleValue()));
		});

		max.addListener((obs, oldv, newv) -> {
			if (Double.isFinite(newv.doubleValue()))
				this.max.setText(Double.toString(newv.doubleValue()));
		});
	}

	public Node getPane()
	{
		return pane;
	}

	public static enum TYPE
	{
		RAW, LABEL, CHANNEL
	}

	public static class DoubleFilter implements UnaryOperator<Change>
	{

		@Override
		public Change apply(final Change t)
		{
			final String input = t.getText();
			return input.matches("\\d*(\\.\\d*)?") ? t : null;
		}
	}

	public double[] getResolution()
	{
		return asArray(
				resolution.textX().textProperty(),
				resolution.textY().textProperty(),
				resolution.textZ().textProperty()
		              );
	}

	public double[] getOffset()
	{
		return asArray(offset.textX().textProperty(), offset.textY().textProperty(), offset.textZ().textProperty());
	}

	public double[] asArray(final ObservableStringValue... values)
	{
		return Arrays.stream(values).map(ObservableValue::getValue).mapToDouble(Double::parseDouble).toArray();
	}

	public double min()
	{
		final String text = min.getText();
		return text.length() > 0 ? Double.parseDouble(min.getText()) : Double.NaN;
	}

	public double max()
	{
		final String text = max.getText();
		return text.length() > 0 ? Double.parseDouble(max.getText()) : Double.NaN;
	}

	public void bindDataTypeTo(final ObjectProperty<TYPE> dataType)
	{
		this.dataType.bind(dataType);
	}

	private static void addToGrid(final GridPane grid, final int startCol, final int row, final Node... nodes)
	{
		for (int col = startCol, i = 0; i < nodes.length; ++i, ++col)
			grid.add(nodes[i], col, row);
	}

	private static void formatLabels(final Label... labels)
	{
		for (int i = 0; i < labels.length; ++i)
		{
			labels[i].setAlignment(Pos.BASELINE_CENTER);
			labels[i].setPrefWidth(TEXTFIELD_WIDTH);
		}
	}

	public Button getRevertButton()
	{
		return revertButton;
	}

	public ObservableObjectValue<AxisOrder> axisOrderProperty()
	{
		return this.axisOrder;
	}

}
