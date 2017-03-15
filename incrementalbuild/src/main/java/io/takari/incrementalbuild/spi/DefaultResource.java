package io.takari.incrementalbuild.spi;

import io.takari.incrementalbuild.MessageSeverity;
import io.takari.incrementalbuild.Output;
import io.takari.incrementalbuild.Resource;

import java.io.File;

/**
 * @noinstantiate clients are not expected to instantiate this class
 */
public class DefaultResource<T> extends DefaultResourceMetadata<T> implements Resource<T> {

  protected DefaultResource(AbstractBuildContext context, DefaultBuildContextState state,
      T resource) {
    super(context, state, resource);
  }

  @Override
  public DefaultOutput associateOutput(Output<File> output) {
    if (!(output instanceof DefaultOutput)) {
      throw new IllegalArgumentException();
    }
    return context.associate(this, (DefaultOutput) output);
  }

  @Override
  public DefaultOutput associateOutput(File outputFile) {
    return context.associate(this, outputFile);
  }

  @Override
  public void addMessage(int line, int column, String message, MessageSeverity severity,
      Throwable cause) {
    context.addMessage(getResource(), line, column, message, severity, cause);
  }

}
