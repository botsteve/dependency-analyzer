package io.botsteve.dependencyanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class DependencyNode {

  @JsonProperty("groupId")
  private String groupId;

  @JsonProperty("artifactId")
  private String artifactId;

  @JsonProperty("version")
  private String version;

  @JsonProperty("scmUrl")
  private String scmUrl;

  @JsonProperty("scope")
  private String scope;

  @JsonProperty("children")
  private List<DependencyNode> children;

  @JsonIgnore
  private StringProperty checkoutTag = new SimpleStringProperty("");

  @JsonIgnore
  private StringProperty buildWith = new SimpleStringProperty("");

  @JsonIgnore
  private BooleanProperty selected = new SimpleBooleanProperty(false);

  public DependencyNode() {
  }

  public DependencyNode(String groupId, String artifactId, String version) {
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.version = version;
  }

  public DependencyNode(String groupId, String artifactId, String version, String scope) {
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.version = version;
    this.scope = scope;
  }

  public String getBuildWith() {
    return buildWith.get();
  }

  public StringProperty buildWithProperty() {
    return buildWith;
  }

  public void setBuildWith(String buildWith) {
    this.buildWith.set(buildWith);
  }

  public BooleanProperty selectedProperty() {
    return selected;
  }

  public boolean isSelected() {
    return selected.get();
  }

  public void setSelected(boolean selected) {
    this.selected.set(selected);
  }

  public void setCheckoutTag(String checkoutTag) {
    this.checkoutTag.set(checkoutTag);
  }

  public String getCheckoutTag() {
    return this.checkoutTag.get();
  }

  public String getGroupId() {
    return groupId;
  }

  public String getArtifactId() {
    return artifactId;
  }

  public String getVersion() {
    return version;
  }

  public void setGroupId(String groupId) {
    this.groupId = groupId;
  }

  public void setArtifactId(String artifactId) {
    this.artifactId = artifactId;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getScmUrl() {
    return scmUrl;
  }

  public void setScmUrl(String scmUrl) {
    this.scmUrl = scmUrl;
  }

  public String getScope() {
    return scope;
  }

  public void setScope(String scope) {
    this.scope = scope;
  }

  public List<DependencyNode> getChildren() {
    return children;
  }

  public void setChildren(List<DependencyNode> children) {
    this.children = children;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DependencyNode that = (DependencyNode) o;
    return Objects.equals(groupId, that.groupId) && Objects.equals(artifactId, that.artifactId)
           && Objects.equals(version, that.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(groupId, artifactId, version);
  }
}
