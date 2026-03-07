package io.botsteve.dependencyanalyzer.components;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.input.MouseButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import io.botsteve.dependencyanalyzer.model.DependencyNode;
import io.botsteve.dependencyanalyzer.utils.FxUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ColumnsComponent {

  private static final Logger LOG = LoggerFactory.getLogger(ColumnsComponent.class);

  public ColumnsComponent() {
  }

  public ColumnsComponent(TableViewComponent tableViewComponent) {
    this();
  }

  public TreeTableColumn<DependencyNode, String> getBuildWithColumn() {
    TreeTableColumn<DependencyNode, String> buildWithColumn = new TreeTableColumn<>("Output");
    buildWithColumn.setCellValueFactory(param -> getSimpleStringProperty(param.getValue().getValue().getBuildWith()));
    buildWithColumn.setCellFactory(column -> new javafx.scene.control.TreeTableCell<>() {
      private String outputValue;

      {
        setOnMouseClicked(event -> {
          if (event.getButton() == MouseButton.PRIMARY
              && event.getClickCount() == 2
              && outputValue != null
              && !outputValue.isBlank()) {
            FxUtils.showTextDialog("Output Details", "Full output", outputValue);
          }
        });
      }

      @Override
      protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          outputValue = null;
          setText(null);
          getStyleClass().removeAll("status-ok", "status-failed");
        } else {
          outputValue = item;
          setText(item);
          getStyleClass().removeAll("status-ok", "status-failed");
          if (item.contains("Build OK")) {
            getStyleClass().add("status-ok");
          } else if (item.contains("Failed") || item.contains("Internal Error")) {
            getStyleClass().add("status-failed");
          }
        }
      }
    });
    buildWithColumn.setMinWidth(100);
    return buildWithColumn;
  }

  public TreeTableColumn<DependencyNode, String> getCheckoutTagColumn() {
    TreeTableColumn<DependencyNode, String> checkoutTag = new TreeTableColumn<>("Repo checkout tag");
    checkoutTag.setCellValueFactory(param -> getSimpleStringProperty(param.getValue().getValue().getCheckoutTag()));
    return checkoutTag;
  }

  public TreeTableColumn<DependencyNode, String> getSCMTreeTableColumn() {
    TreeTableColumn<DependencyNode, String> scmColumn = new TreeTableColumn<>("SCM URL");
    scmColumn.setCellValueFactory(param -> getSimpleStringProperty(getSCMColumnValue(param)));
    scmColumn.setCellFactory(column -> new javafx.scene.control.TreeTableCell<>() {
      private final javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink();
      {
        link.setOnAction(event -> {
          String url = itemProperty().get();
          if (url != null && !url.isEmpty() && !"SCM URL not found".equals(url)) {
            new Thread(() -> {
              try {
                if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                  Runtime.getRuntime().exec(new String[]{"open", url});
                } else if (System.getProperty("os.name").toLowerCase().contains("win")) {
                  Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
                }
              } catch (Exception e) {
                LOG.warn("Failed opening SCM URL in system browser: {}", url, e);
              }
            }).start();
          }
        });
      }
      @Override
      protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null || item.isEmpty() || "SCM URL not found".equals(item)) {
          setGraphic(null);
          setText(item);
          if ("SCM URL not found".equals(item)) {
            setStyle("-fx-text-fill: #95a5a6;");
          } else {
            setStyle("");
          }
        } else {
          link.setText(item);
          setGraphic(link);
          setText(null);
          setStyle("");
        }
      }
    });
    return scmColumn;
  }

  public TreeTableColumn<DependencyNode, Boolean> getSelectTreeTableColumn() {
    TreeTableColumn<DependencyNode, Boolean> selectColumn = new TreeTableColumn<>("Select");
    selectColumn.setCellValueFactory(param -> param.getValue().getValue().selectedProperty());
    selectColumn.setCellFactory(column -> new TreeTableCell<>() {
      private static final String FOURTH_PARTY_CELL_STYLE_CLASS = "fourth-party-select-cell";
      private static final String THIRD_PARTY_CELL_STYLE_CLASS = "third-party-select-cell";
      private static final String FOURTH_PARTY_CHECKBOX_STYLE_CLASS = "fourth-party-check-box";
      private static final String THIRD_PARTY_CHECKBOX_STYLE_CLASS = "third-party-check-box";
      private final CheckBox checkBox = new CheckBox();
      private javafx.beans.property.BooleanProperty boundProperty;

      {
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setAlignment(Pos.CENTER_LEFT);
      }

      @Override
      protected void updateItem(Boolean item, boolean empty) {
        super.updateItem(item, empty);

        if (boundProperty != null) {
          checkBox.selectedProperty().unbindBidirectional(boundProperty);
          boundProperty = null;
        }

        getStyleClass().removeAll(FOURTH_PARTY_CELL_STYLE_CLASS, THIRD_PARTY_CELL_STYLE_CLASS);
        checkBox.getStyleClass().removeAll(FOURTH_PARTY_CHECKBOX_STYLE_CLASS, THIRD_PARTY_CHECKBOX_STYLE_CLASS);

        if (empty) {
          setText(null);
          setGraphic(null);
          setPadding(Insets.EMPTY);
          return;
        }

        TreeItem<DependencyNode> treeItem = getTreeTableView() == null || getIndex() < 0
            ? null
            : getTreeTableView().getTreeItem(getIndex());
        if (treeItem == null || treeItem.getValue() == null) {
          setText(null);
          setGraphic(null);
          setPadding(Insets.EMPTY);
          return;
        }

        boundProperty = treeItem.getValue().selectedProperty();
        checkBox.selectedProperty().bindBidirectional(boundProperty);
        checkBox.setDisable(getTreeTableView() == null || !getTreeTableView().isEditable() || !getTableColumn().isEditable());

        int treeLevel = getTreeTableView().getTreeItemLevel(treeItem);
        boolean isFourthPartyOrDeeper = treeLevel >= 2;
        if (isFourthPartyOrDeeper) {
          getStyleClass().add(FOURTH_PARTY_CELL_STYLE_CLASS);
          checkBox.getStyleClass().add(FOURTH_PARTY_CHECKBOX_STYLE_CLASS);
          setPadding(new Insets(0, 0, 0, Math.min(12, treeLevel * 4)));
        } else {
          getStyleClass().add(THIRD_PARTY_CELL_STYLE_CLASS);
          checkBox.getStyleClass().add(THIRD_PARTY_CHECKBOX_STYLE_CLASS);
          setPadding(Insets.EMPTY);
        }

        setGraphic(checkBox);
      }
    });
    selectColumn.setMinWidth(50); // Set a fixed width for the checkbox column
    selectColumn.setMaxWidth(50);
    selectColumn.setEditable(true);
    return selectColumn;
  }

  public TreeTableColumn<DependencyNode, String> getDependencyTreeTableColumn() {
    TreeTableColumn<DependencyNode, String> dependencyColumn = new TreeTableColumn<>("Dependency");
    dependencyColumn.setCellValueFactory(param -> getSimpleStringProperty(getDependencyColumnValue(param)));
    return dependencyColumn;
  }

  public TreeTableColumn<DependencyNode, String> getScopeColumn() {
    TreeTableColumn<DependencyNode, String> scopeColumn = new TreeTableColumn<>("Scope");
    scopeColumn.setCellValueFactory(param -> {
      String scope = param.getValue().getValue().getScope();
      return getSimpleStringProperty(scope != null ? scope : "");
    });
    scopeColumn.setMinWidth(80);
    return scopeColumn;
  }


  private String getSCMColumnValue(TreeTableColumn.CellDataFeatures<DependencyNode, String> param) {
    return param.getValue().getValue().getScmUrl();
  }

  private String getDependencyColumnValue(TreeTableColumn.CellDataFeatures<DependencyNode, String> param) {
    return param.getValue().getValue().getGroupId() + ":" +
           param.getValue().getValue().getArtifactId() + ":" +
           param.getValue().getValue().getVersion();
  }

  private SimpleStringProperty getSimpleStringProperty(String value) {
    return new SimpleStringProperty(value);
  }

  public void configureColumnsWidthStyle(TreeTableColumn<DependencyNode, Boolean> selectColumn,
                                         TreeTableColumn<DependencyNode, String> dependencyColumn,
                                         TreeTableColumn<DependencyNode, String> scopeColumn,
                                         TreeTableColumn<DependencyNode, String> scmColumn,
                                         TreeTableColumn<DependencyNode, String> checkoutTagColumn,
                                         TreeTableColumn<DependencyNode, String> buildWithColumn) {
    // Set initial widths to distribute space roughly, but allow resizing
    dependencyColumn.setPrefWidth(230);
    scopeColumn.setPrefWidth(110);
    scmColumn.setPrefWidth(220);
    checkoutTagColumn.setPrefWidth(170);
    buildWithColumn.setPrefWidth(220);
  }
}
