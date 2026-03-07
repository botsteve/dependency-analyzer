package io.botsteve.dependencyanalyzer.tasks;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import io.botsteve.dependencyanalyzer.exception.DependencyAnalyzerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckoutTagsTask {

  private static final Logger log = LoggerFactory.getLogger(CheckoutTagsTask.class);

  private static final String NO_TAG_FOUND = "No tags found matching the pattern for repo %s with version %s";
  private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+){1,3}(?:[-._A-Za-z0-9+]*)?)");

  public static String checkoutTag(File file, String version) throws GitAPIException, IOException {
    try (Git git = Git.open(file)) {
      List<Ref> filteredSortedTags = findMatchingTags(git, version);

      if (filteredSortedTags.isEmpty()) {
        git.fetch()
            .setTagOpt(TagOpt.FETCH_TAGS)
            .call();
        filteredSortedTags = findMatchingTags(git, version);
      }

      log.debug("Available tags: {}", filteredSortedTags.stream()
                                         .map(Ref::getName)
                                         .collect(Collectors.joining(", ")));

      if (!filteredSortedTags.isEmpty()) {
        Ref tagToCheckout = filteredSortedTags.getFirst();
        checkoutTag(git, tagToCheckout);
        return tagToCheckout.getName();
      } else {
        var depViewerException = new DependencyAnalyzerException(String.format(NO_TAG_FOUND, file.getName(), version));
        log.error("No tags found matching the pattern.", depViewerException);
        throw depViewerException;
      }
    }
  }

  private static List<Ref> findMatchingTags(Git git, String version) throws GitAPIException {
    return git.tagList().call().stream()
        .filter(tag -> isMatchingVersionTag(version, tag))
        .sorted(new TagComparator(git))
        .toList();
  }

  private static boolean isMatchingVersionTag(String version, Ref tag) {
    var strippedTag = tag.getName().replace("refs/tags/", "").toLowerCase();
    boolean match = versionsMatch(version, strippedTag);
    var normalizedTag = normalizeVersion(strippedTag);
    var normalizedVersion = normalizeVersion(version);
    log.debug("Tag '{}' normalized to '{}' for requested version '{}' normalized to '{}' => match={} ",
        tag.getName(), normalizedTag, version, normalizedVersion, match);
    return match;
  }

  static boolean versionsMatch(String requestedVersion, String tagName) {
    String normalizedTag = normalizeVersion(tagName);
    String normalizedVersion = normalizeVersion(requestedVersion);
    return isVersionPrefixMatch(normalizedTag, normalizedVersion);
  }

  static class TagComparator implements Comparator<Ref> {

    private final Git git;

    public TagComparator(Git git) {
      this.git = git;
    }

    @Override
    public int compare(Ref tag1, Ref tag2) {
      try {
        Date date1 = getTagDate(tag1);
        Date date2 = getTagDate(tag2);
        return date2.compareTo(date1);
      } catch (IOException e) {
        throw new RuntimeException("Failed to compare tags", e);
      }
    }

    private Date getTagDate(Ref tag) throws IOException {
      try (RevWalk walk = new RevWalk(git.getRepository())) {
        ObjectId objectId = resolveObjectId(tag);
        RevCommit commit = walk.parseCommit(objectId);
        return commit.getCommitterIdent().getWhen();
      }
    }

    private ObjectId resolveObjectId(Ref tag) {
      // Handle annotated tags
      if (tag.getPeeledObjectId() != null) {
        return tag.getPeeledObjectId();
      }
      // Handle lightweight tags
      return tag.getObjectId();
    }
  }


  static void checkoutTag(Git git, Ref tag) throws GitAPIException {
    git.checkout()
        .setName(tag.getName())
        .call();
    log.debug("Checked out repo {} with tag: {}", git.getRepository().getDirectory().getName(), tag.getName());
  }

  public static String normalizeVersion(String version) {
    if (version == null) {
      return "";
    }
    String sanitized = version.replace("refs/tags/", "").trim().toLowerCase();
    Matcher matcher = VERSION_PATTERN.matcher(sanitized);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return sanitized;
  }

  private static boolean isVersionPrefixMatch(String value, String prefix) {
    if (value == null || prefix == null || value.isBlank() || prefix.isBlank()) {
      return false;
    }
    if (!value.startsWith(prefix)) {
      return false;
    }
    if (value.length() == prefix.length()) {
      return true;
    }
    char boundary = value.charAt(prefix.length());
    return boundary == '.' || boundary == '-' || boundary == '_' || !Character.isDigit(boundary);
  }
}
