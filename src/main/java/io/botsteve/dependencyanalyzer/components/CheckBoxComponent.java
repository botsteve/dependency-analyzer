package io.botsteve.dependencyanalyzer.components;

import javafx.scene.control.TreeItem;
import lombok.Data;
import io.botsteve.dependencyanalyzer.model.DependencyNode;

@Data
public class CheckBoxComponent {

  private TableViewComponent tableViewComponent;

  public CheckBoxComponent(TableViewComponent tableViewComponent) {
    this.tableViewComponent = tableViewComponent;
  }

  public void configureCheckBoxAction() {
    tableViewComponent.getSelectAllCheckBox().setOnAction(event -> {
      boolean selectAll = tableViewComponent.getSelectAllCheckBox().isSelected();
      for (TreeItem<DependencyNode> item : tableViewComponent.getTreeTableView().getRoot().getChildren()) {
        item.getValue().setSelected(selectAll);
      }
    });
  }
}
