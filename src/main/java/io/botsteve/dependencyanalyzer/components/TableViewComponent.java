package io.botsteve.dependencyanalyzer.components;


import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import lombok.Data;
import io.botsteve.dependencyanalyzer.model.DependencyNode;

@Data
public class TableViewComponent {

  private final TreeTableView<DependencyNode> treeTableView = new TreeTableView<>();
  private ObservableSet<DependencyNode> allDependencies = FXCollections.observableSet();
  private ObservableSet<DependencyNode> selectedDependencies = FXCollections.observableSet();
  private static final String ALL_SCOPES = "All Scopes";
  private final Label filterLabel = new Label("Fuzzy:");
  private final Label excludeFilterLabel = new Label("Exclude:");
  private final Label statsLabel = new Label("Dependencies: 0");
  private final TextField filterInput = new TextField();
  private final TextField excludeFilterInput = new TextField();
  private final MenuButton scopeFilterMenu = new MenuButton(ALL_SCOPES);
  private final Set<String> selectedScopes = new LinkedHashSet<>();
  private final CheckBox selectAllCheckBox = new CheckBox("Select All");
  private final CheckBox cleanUpCheckBox = new CheckBox("Clean up existing repos");
  private final Label projectNameLabel = new Label("No project loaded");
  private String projectName;
  private final Map<DependencyNode, ChangeListener<Boolean>> selectionListeners = new IdentityHashMap<>();
  private static final Comparator<DependencyNode> GAV_COMPARATOR = Comparator
      .comparing((DependencyNode n) -> safe(n.getGroupId()))
      .thenComparing(n -> safe(n.getArtifactId()))
      .thenComparing(n -> safe(n.getVersion()));


  public TableViewComponent() {
    treeTableView.setShowRoot(false);
    treeTableView.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    treeTableView.setEditable(true);

    javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();
    javafx.scene.control.MenuItem copyScmItem = new javafx.scene.control.MenuItem("Copy SCM URL");
    copyScmItem.setOnAction(event -> {
      DependencyNode selected = treeTableView.getSelectionModel().getSelectedItem() == null ? null : treeTableView.getSelectionModel().getSelectedItem().getValue();
      if (selected != null && selected.getScmUrl() != null) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(selected.getScmUrl());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
      }
    });

    javafx.scene.control.MenuItem copyGavItem = new javafx.scene.control.MenuItem("Copy GAV (group:artifact:version)");
    copyGavItem.setOnAction(event -> {
      DependencyNode selected = treeTableView.getSelectionModel().getSelectedItem() == null ? null : treeTableView.getSelectionModel().getSelectedItem().getValue();
      if (selected != null) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(selected.getGroupId() + ":" + selected.getArtifactId() + ":" + selected.getVersion());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
      }
    });

    javafx.scene.control.MenuItem copyCellItem = new javafx.scene.control.MenuItem("Copy Cell Value");
    copyCellItem.setOnAction(event -> copyFocusedCellValue());

    contextMenu.getItems().addAll(copyScmItem, copyGavItem, copyCellItem);
    treeTableView.setContextMenu(contextMenu);
    installKeyboardShortcuts();

    filterInput.setPromptText("Fuzzy search 3rd-party dependencies...");
    excludeFilterInput.setPromptText("Exclude 3rd-party dependencies...");
    filterLabel.setTooltip(new Tooltip("Shortcuts: E expand, C collapse, Shift+E/C recursive, Ctrl/Cmd+C copy cell"));
    filterInput.textProperty().addListener((observable, oldValue, newValue) -> {
      applyFilters();
    });
    excludeFilterInput.textProperty().addListener((observable, oldValue, newValue) -> {
      applyFilters();
    });
    scopeFilterMenu.setMinWidth(120);
    cleanUpCheckBox.setSelected(false);
  }

  /**
   * Reapplies fuzzy/exclude/scope filters against currently loaded dependencies.
   */
  public void applyFilters() {
    if (this.allDependencies != null) {
      updateTreeView(this.allDependencies);
    }
  }

  /**
   * Refreshes tree view content after dependency metadata updates.
   */
  public void updateTreeViewWithFilteredDependencies(String newValue) {
    applyFilters();
  }

  /**
   * Filters dependencies using fuzzy text and scope selections.
   */
  public Set<DependencyNode> filterDependencies(String filterText, Set<String> scopes) {
    return filterDependencies(filterText, excludeFilterInput.getText(), scopes);
  }

  /**
   * Filters dependencies using fuzzy include text, exclude text, and scope selections.
   */
  public Set<DependencyNode> filterDependencies(String fuzzyFilterText, String excludeFilterText, Set<String> scopes) {
    Set<DependencyNode> visibleRoots = new LinkedHashSet<>();
    for (DependencyNode node : sortedNodes(allDependencies)) {
      if (createFilteredRootTreeItem(node, fuzzyFilterText, excludeFilterText, scopes) != null) {
        visibleRoots.add(node);
      }
    }
    return visibleRoots;
  }

  private boolean isTextMatchingFilter(DependencyNode node, String fuzzyFilterText, String excludeFilterText) {
    String dependencyText = node.getGroupId() + ":" + node.getArtifactId() + ":" + node.getVersion();
    if (!matchesFilterText(dependencyText, fuzzyFilterText)) {
      return false;
    }
    return !isExcludedByText(dependencyText, excludeFilterText);
  }

  private boolean isScopeMatchingFilter(DependencyNode node, Set<String> scopes) {
    if (scopes == null || scopes.isEmpty()) {
      return true; // No scopes selected = show all
    }
    String nodeScope = node.getScope();
    return nodeScope != null && scopes.contains(nodeScope);
  }

  /**
   * Replaces current dependency model, rebinds selection propagation, and rebuilds scope menu options.
   */
  public void setAllDependencies(ObservableSet<DependencyNode> allDependencies) {
    unregisterSelectionListeners(this.allDependencies);
    selectionListeners.clear();
    this.allDependencies = allDependencies;
    selectedDependencies.clear();
    populateScopeFilter(allDependencies);
    for (DependencyNode node : allDependencies) {
      resetSelectionRecursively(node);
      bindSelectionRecursively(node);
    }
  }

  private void resetSelectionRecursively(DependencyNode node) {
    if (node == null) {
      return;
    }
    node.setSelected(false);
    if (node.getChildren() == null) {
      return;
    }
    for (DependencyNode child : node.getChildren()) {
      resetSelectionRecursively(child);
    }
  }

  private void bindSelectionRecursively(DependencyNode node) {
    if (node == null) {
      return;
    }

    if (node.isSelected()) {
      selectedDependencies.add(node);
    }

    ChangeListener<Boolean> existingListener = selectionListeners.remove(node);
    if (existingListener != null) {
      node.selectedProperty().removeListener(existingListener);
    }

    ChangeListener<Boolean> listener = (obs, wasSelected, isNowSelected) -> {
      if (isNowSelected) {
        selectedDependencies.add(node);
      } else {
        selectedDependencies.remove(node);
      }
      propagateSelectionToChildren(node.getChildren(), isNowSelected);
    };
    selectionListeners.put(node, listener);
    node.selectedProperty().addListener(listener);

    if (node.getChildren() == null) {
      return;
    }
    for (DependencyNode child : node.getChildren()) {
      bindSelectionRecursively(child);
    }
  }

  private void propagateSelectionToChildren(List<DependencyNode> children, boolean selected) {
    if (children == null) {
      return;
    }
    for (DependencyNode child : children) {
      if (child == null) {
        continue;
      }
      if (child.isSelected() != selected) {
        child.setSelected(selected);
      }
      if (child.getChildren() != null && !child.getChildren().isEmpty()) {
        propagateSelectionToChildren(child.getChildren(), selected);
      }
    }
  }

  private void unregisterSelectionListeners(Set<DependencyNode> dependencies) {
    if (dependencies == null) {
      return;
    }
    for (DependencyNode node : dependencies) {
      unregisterSelectionListenerRecursively(node);
    }
  }

  private void unregisterSelectionListenerRecursively(DependencyNode node) {
    if (node == null) {
      return;
    }
    ChangeListener<Boolean> listener = selectionListeners.remove(node);
    if (listener != null) {
      node.selectedProperty().removeListener(listener);
    }
    if (node.getChildren() == null) {
      return;
    }
    for (DependencyNode child : node.getChildren()) {
      unregisterSelectionListenerRecursively(child);
    }
  }

  /**
   * Populates the scope filter MenuButton with CheckMenuItems for each unique scope.
   */
  private void populateScopeFilter(Set<DependencyNode> dependencies) {
    Set<String> scopes = new TreeSet<>(); // TreeSet for alphabetical ordering
    collectScopes(dependencies, scopes);

    scopeFilterMenu.getItems().clear();
    selectedScopes.clear();

    // Add "All Scopes" toggle item
    CheckMenuItem allItem = new CheckMenuItem(ALL_SCOPES);
    allItem.setSelected(true);
    allItem.setOnAction(event -> {
      if (allItem.isSelected()) {
        // Deselect all individual scope items
        selectedScopes.clear();
        scopeFilterMenu.getItems().stream()
            .filter(item -> item instanceof CheckMenuItem && !ALL_SCOPES.equals(item.getText()))
            .forEach(item -> ((CheckMenuItem) item).setSelected(false));
      }
      updateScopeButtonText();
      applyFilters();
    });
    scopeFilterMenu.getItems().add(allItem);

    // Add individual scope items
    for (String scope : scopes) {
      CheckMenuItem item = new CheckMenuItem(scope);
      item.setOnAction(event -> {
        if (item.isSelected()) {
          selectedScopes.add(scope);
          // Uncheck "All Scopes" when a specific scope is selected
          allItem.setSelected(false);
        } else {
          selectedScopes.remove(scope);
          // If nothing is selected, re-check "All Scopes"
          if (selectedScopes.isEmpty()) {
            allItem.setSelected(true);
          }
        }
        updateScopeButtonText();
        applyFilters();
      });
      scopeFilterMenu.getItems().add(item);
    }

    updateScopeButtonText();
  }

  /**
   * Updates the MenuButton label to reflect the current selection.
   */
  private void updateScopeButtonText() {
    if (selectedScopes.isEmpty()) {
      scopeFilterMenu.setText(ALL_SCOPES);
    } else if (selectedScopes.size() == 1) {
      scopeFilterMenu.setText(selectedScopes.iterator().next());
    } else {
      scopeFilterMenu.setText(formatScopeSelection(selectedScopes, 25));
    }
  }

  /**
   * Recursively collects all unique scope values from dependencies and their children.
   */
  private void collectScopes(Set<DependencyNode> dependencies, Set<String> scopes) {
    for (DependencyNode node : dependencies) {
      if (node.getScope() != null && !node.getScope().isEmpty()) {
        scopes.add(node.getScope());
      }
      if (node.getChildren() != null) {
        collectScopes(new HashSet<>(node.getChildren()), scopes);
      }
    }
  }

  /**
   * Rebuilds the tree table root content from provided dependencies and active filter state.
   */
  public void updateTreeView(Set<DependencyNode> dependencies) {
    TreeItem<DependencyNode> rootItem = createRootTreeItem();
    rootItem.setExpanded(true);
    populateTreeItems(rootItem, dependencies, filterInput.getText(), excludeFilterInput.getText(), selectedScopes);
    treeTableView.setRoot(rootItem);
    treeTableView.refresh();
    statsLabel.setText("Dependencies: " + rootItem.getChildren().size());
  }

  private TreeItem<DependencyNode> createRootTreeItem() {
    return new TreeItem<>(new DependencyNode("Root", "", ""));
  }

  private void populateTreeItems(TreeItem<DependencyNode> rootItem,
                                 Set<DependencyNode> dependencies,
                                 String fuzzyFilterText,
                                 String excludeFilterText,
                                 Set<String> scopes) {
    for (DependencyNode node : sortedNodes(dependencies)) {
      TreeItem<DependencyNode> filteredItem = createFilteredRootTreeItem(node, fuzzyFilterText, excludeFilterText, scopes);
      if (filteredItem != null) {
        rootItem.getChildren().add(filteredItem);
      }
    }
  }

  private TreeItem<DependencyNode> createTreeItem(DependencyNode node) {
    TreeItem<DependencyNode> treeItem = new TreeItem<>(node);
    addChildrenToTreeItem(node, treeItem);
    return treeItem;
  }

  private TreeItem<DependencyNode> createFilteredRootTreeItem(DependencyNode node,
                                                              String fuzzyFilterText,
                                                              String excludeFilterText,
                                                              Set<String> scopes) {
    if (node == null) {
      return null;
    }

    boolean textMatches = isTextMatchingFilter(node, fuzzyFilterText, excludeFilterText);
    if (!textMatches) {
      return null;
    }

    return createScopeFilteredTreeItem(node, scopes);
  }

  private TreeItem<DependencyNode> createScopeFilteredTreeItem(DependencyNode node, Set<String> scopes) {
    if (node == null) {
      return null;
    }

    TreeItem<DependencyNode> treeItem = new TreeItem<>(node);
    if (node.getChildren() != null) {
      for (DependencyNode child : sortedNodes(new HashSet<>(node.getChildren()))) {
        TreeItem<DependencyNode> childItem = createScopeFilteredTreeItem(child, scopes);
        if (childItem != null) {
          treeItem.getChildren().add(childItem);
        }
      }
    }

    boolean scopeMatches = isScopeMatchingFilter(node, scopes);
    if (!scopeMatches && treeItem.getChildren().isEmpty()) {
      return null;
    }
    return treeItem;
  }

  private void addChildrenToTreeItem(DependencyNode node, TreeItem<DependencyNode> treeItem) {
    if (node.getChildren() != null) {
      for (DependencyNode child : sortedNodes(new HashSet<>(node.getChildren()))) {
        treeItem.getChildren().add(createTreeItem(child));
      }
    }
  }

  public TreeTableView<DependencyNode> getTreeTableView() {
    return treeTableView;
  }

  public ObservableSet<DependencyNode> getAllDependencies() {
    return allDependencies;
  }

  public ObservableSet<DependencyNode> getSelectedDependencies() {
    return selectedDependencies;
  }

  public void setSelectedDependencies(ObservableSet<DependencyNode> selectedDependencies) {
    this.selectedDependencies = selectedDependencies;
  }

  public TextField getFilterInput() {
    return filterInput;
  }

  public TextField getExcludeFilterInput() {
    return excludeFilterInput;
  }

  public CheckBox getSelectAllCheckBox() {
    return selectAllCheckBox;
  }

  public CheckBox getCleanUpCheckBox() {
    return cleanUpCheckBox;
  }

  public Label getProjectNameLabel() {
    return projectNameLabel;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  /**
   * Builds the filtering/selection utility bar displayed above the dependency table.
   */
  public HBox creatToolsBox() {
    HBox box = new HBox(15);
    box.setAlignment(Pos.CENTER_LEFT);
    box.setPadding(new javafx.geometry.Insets(5, 15, 5, 15));
    box.getStyleClass().add("tool-bar");
    
    HBox filterBox = new HBox(8, filterLabel, filterInput, excludeFilterLabel, excludeFilterInput);
    filterBox.setAlignment(Pos.CENTER_LEFT);
    
    HBox scopeBox = new HBox(8, new Label("Scope:"), scopeFilterMenu);
    scopeBox.setAlignment(Pos.CENTER_LEFT);
    
    HBox projectBox = new HBox(8, new Label("Project:"), projectNameLabel);
    projectBox.setAlignment(Pos.CENTER_LEFT);
    projectNameLabel.getStyleClass().add("project-name-label");
    
    javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
    HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
    
    box.getChildren().addAll(filterBox, scopeBox, projectBox, spacer, statsLabel, selectAllCheckBox, cleanUpCheckBox);
    return box;
  }

  private void installKeyboardShortcuts() {
    treeTableView.setOnKeyPressed(event -> {
      TreeItem<DependencyNode> selected = treeTableView.getSelectionModel().getSelectedItem();
      if (event.getCode() == KeyCode.E && selected != null && event.isShiftDown()) {
        setExpandedRecursively(selected, true);
      } else if (event.getCode() == KeyCode.C && selected != null && event.isShiftDown()) {
        setExpandedRecursively(selected, false);
      } else if (event.getCode() == KeyCode.E && selected != null) {
        selected.setExpanded(true);
      } else if (event.getCode() == KeyCode.C && selected != null) {
        selected.setExpanded(false);
      } else if (event.getCode() == KeyCode.C && event.isShortcutDown()) {
        copyFocusedCellValue();
      }
    });
  }

  private static void setExpandedRecursively(TreeItem<DependencyNode> item, boolean expanded) {
    if (item == null) {
      return;
    }
    item.setExpanded(expanded);
    for (TreeItem<DependencyNode> child : item.getChildren()) {
      setExpandedRecursively(child, expanded);
    }
  }

  private void copyFocusedCellValue() {
    TreeTablePosition<DependencyNode, ?> pos = treeTableView.getFocusModel().getFocusedCell();
    if (pos == null) {
      return;
    }
    TreeTableColumn<DependencyNode, ?> column = pos.getTableColumn();
    TreeItem<DependencyNode> treeItem = pos.getTreeItem();
    if (column == null || treeItem == null) {
      return;
    }
    Object cellData = column.getCellData(treeItem);
    ClipboardContent content = new ClipboardContent();
    content.putString(cellData == null ? "" : cellData.toString());
    Clipboard.getSystemClipboard().setContent(content);
  }

  private static Set<DependencyNode> sortedNodes(Set<DependencyNode> nodes) {
    Set<DependencyNode> sorted = new java.util.LinkedHashSet<>();
    if (nodes == null) {
      return sorted;
    }
    nodes.stream().sorted(GAV_COMPARATOR).forEach(sorted::add);
    return sorted;
  }

  static String formatScopeSelection(Set<String> scopes, int maxLength) {
    String joined = String.join(", ", scopes);
    if (joined.length() <= maxLength) {
      return joined;
    }
    String first = scopes.iterator().next();
    return first + " (+" + (scopes.size() - 1) + ")";
  }

  static boolean matchesFilterText(String dependencyText, String filterText) {
    if (filterText == null || filterText.isEmpty()) {
      return true;
    }
    String dependency = normalizeFuzzyToken(dependencyText);
    String filter = normalizeFuzzyToken(filterText);
    if (dependency.isBlank() || filter.isBlank()) {
      return true;
    }

    if (dependency.contains(filter) || isSubsequence(dependency, filter)) {
      return true;
    }

    String[] parts = dependency.split("[^a-z0-9]+");
    int maxDistance = maxDistance(filter.length());
    for (String part : parts) {
      if (part == null || part.isBlank()) {
        continue;
      }
      if (part.contains(filter) || isSubsequence(part, filter)) {
        return true;
      }
      if (Math.abs(part.length() - filter.length()) <= maxDistance
          && levenshteinDistance(part, filter) <= maxDistance) {
        return true;
      }
    }

    if (Math.abs(dependency.length() - filter.length()) <= maxDistance + 4
        && levenshteinDistance(dependency, filter) <= maxDistance + 1) {
      return true;
    }
    return false;
  }

  static boolean matchesExcludeText(String dependencyText, String filterText) {
    return !isExcludedByText(dependencyText, filterText);
  }

  static boolean isExcludedByText(String dependencyText, String excludeFilterText) {
    if (excludeFilterText == null || excludeFilterText.isBlank()) {
      return false;
    }
    String dependency = normalizeFuzzyToken(dependencyText);
    String exclude = normalizeFuzzyToken(excludeFilterText);
    if (dependency.isBlank() || exclude.isBlank()) {
      return false;
    }
    return dependency.contains(exclude);
  }

  private static int maxDistance(int filterLength) {
    if (filterLength <= 4) {
      return 1;
    }
    if (filterLength <= 8) {
      return 2;
    }
    return 3;
  }

  private static String normalizeFuzzyToken(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT).trim();
  }

  private static boolean isSubsequence(String text, String pattern) {
    if (pattern.isBlank()) {
      return true;
    }
    int i = 0;
    for (int j = 0; j < text.length() && i < pattern.length(); j++) {
      if (text.charAt(j) == pattern.charAt(i)) {
        i++;
      }
    }
    return i == pattern.length();
  }

  private static int levenshteinDistance(String left, String right) {
    if (left.equals(right)) {
      return 0;
    }
    if (left.isEmpty()) {
      return right.length();
    }
    if (right.isEmpty()) {
      return left.length();
    }

    int[] previous = new int[right.length() + 1];
    int[] current = new int[right.length() + 1];
    for (int j = 0; j <= right.length(); j++) {
      previous[j] = j;
    }

    for (int i = 1; i <= left.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= right.length(); j++) {
        int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(
            Math.min(current[j - 1] + 1, previous[j] + 1),
            previous[j - 1] + cost);
      }
      int[] tmp = previous;
      previous = current;
      current = tmp;
    }
    return previous[right.length()];
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
