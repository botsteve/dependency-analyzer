package io.botsteve.dependencyanalyzer.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Data
public class CollectingOutputHandler implements InvocationOutputHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CollectingOutputHandler.class);

  private final List<String> output = new ArrayList<>();

  @Override
  public void consumeLine(String line) {
    output.add(line);
    LOG.info(line);
  }

  public List<String> getOutput() {
    return output;
  }
}
