package io.botsteve.dependencyanalyzer.components;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import io.botsteve.dependencyanalyzer.model.DependencyNode;

public class ContextMenuComponent {

  private final TableViewComponent tableViewComponent;

  public ContextMenuComponent(TableViewComponent tableViewComponent) {
    this.tableViewComponent = tableViewComponent;
  }

  public ContextMenu createContextMenu() {
    ContextMenu contextMenu = new ContextMenu();
    var copyMenuItem = createCopyContextMenuItem();
    contextMenu.getItems().add(copyMenuItem);
    return contextMenu;
  }

  private MenuItem createCopyContextMenuItem() {
    MenuItem copyMenuItem = new MenuItem("Copy");

    copyMenuItem.setOnAction(event -> {
      TreeTablePosition<DependencyNode, ?> pos = tableViewComponent.getTreeTableView().getFocusModel().getFocusedCell();
      if (pos != null) {
        TreeTableColumn<DependencyNode, ?> column = pos.getTableColumn();
        TreeItem<DependencyNode> treeItem = pos.getTreeItem();
        Object cellData = column.getCellData(treeItem);
        String contentToCopy = cellData != null ? cellData.toString() : "";

        ClipboardContent content = new ClipboardContent();
        content.putString(contentToCopy);
        Clipboard.getSystemClipboard().setContent(content);
      }
    });
    return copyMenuItem;
  }
}
