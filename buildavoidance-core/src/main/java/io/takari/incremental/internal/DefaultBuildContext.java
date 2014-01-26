package io.takari.incremental.internal;

import io.takari.incremental.BuildContext;
import io.takari.incremental.FileSet;
import io.takari.incremental.FileSetBuilder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// XXX normalize all File parameters. maybe easier to use URI internally.
// XXX maybe use relative URIs to save heap

public abstract class DefaultBuildContext<BuildFailureException extends Exception>
    implements
      BuildContext,
      BuildContextStateManager {

  private final Logger log = LoggerFactory.getLogger(getClass());

  private final File stateFile;

  private final DefaultBuildContextState oldState;

  private final Map<String, byte[]> configuration;

  /**
   * Previous build state does not exist, cannot be read or configuration has changed. When
   * escalated, all input files are considered require processing.
   */
  private final boolean escalated;

  // inputs and outputs

  /**
   * Inputs registered with this build context during this build.
   */
  private final ConcurrentMap<File, DefaultInput> inputs =
      new ConcurrentHashMap<File, DefaultInput>();

  /**
   * Outputs registered with this build context during this build.
   */
  private final ConcurrentMap<File, DefaultOutput> outputs =
      new ConcurrentHashMap<File, DefaultOutput>();

  // direct associations

  private final ConcurrentMap<File, Collection<DefaultOutput>> inputOutputs =
      new ConcurrentHashMap<File, Collection<DefaultOutput>>();

  private final ConcurrentMap<File, Collection<DefaultInput>> outputInputs =
      new ConcurrentHashMap<File, Collection<DefaultInput>>();

  private final ConcurrentMap<File, Collection<File>> inputIncludedInputs =
      new ConcurrentHashMap<File, Collection<File>>();

  // provided/required capabilities

  private final ConcurrentMap<File, Collection<QualifiedName>> inputRequirements =
      new ConcurrentHashMap<File, Collection<QualifiedName>>();

  /**
   * Maps requirement qname to all input that require it.
   */
  private final ConcurrentMap<QualifiedName, Collection<DefaultInput>> requirementInputs =
      new ConcurrentHashMap<QualifiedName, Collection<DefaultInput>>();

  /**
   * Maps output file to capabilities provided by it.
   */
  private final ConcurrentMap<File, Collection<QualifiedName>> outputCapabilities =
      new ConcurrentHashMap<File, Collection<QualifiedName>>();

  // simple key/value pairs

  private final ConcurrentMap<File, Map<String, Serializable>> inputAttributes =
      new ConcurrentHashMap<File, Map<String, Serializable>>();

  // messages

  /**
   * Maps input or included input file to messages generated for the file
   */
  private final ConcurrentMap<File, Collection<Message>> inputMessages =
      new ConcurrentHashMap<File, Collection<Message>>();

  /**
   * Indicates that error messages were reported during this build or not cleared from the previous
   * build.
   */
  private volatile boolean failed;

  public DefaultBuildContext(File stateFile, Map<String, byte[]> configuration) {
    // preconditions
    if (stateFile == null) {
      throw new NullPointerException();
    }
    if (configuration == null) {
      throw new NullPointerException();
    }

    this.stateFile = stateFile;
    this.oldState = loadState(stateFile);

    // TODO clone byte arrays too?
    this.configuration = new HashMap<String, byte[]>(configuration);

    this.escalated = getEscalated();
  }

  private boolean getEscalated() {
    if (oldState == null) {
      log.debug("No previous build state {}", stateFile);
      return true;
    }

    Map<String, byte[]> oldConfiguration = oldState.getConfiguration();

    if (!oldConfiguration.keySet().equals(configuration.keySet())) {
      log.debug("Inconsistent configuration keys, old={}, new={}", oldConfiguration.keySet(),
          configuration.keySet());
      return true;
    }

    Set<String> keys = new TreeSet<String>();
    for (String key : oldConfiguration.keySet()) {
      if (!Arrays.equals(oldConfiguration.get(key), configuration.get(key))) {
        keys.add(key);
      }
    }

    if (!keys.isEmpty()) {
      log.debug("Configuration changed, changed keys={}", keys);
      return true;
    }

    // XXX need a way to debug detailed configuration of changed keys

    return false;
  }

  private DefaultBuildContextState loadState(File stateFile) {
    // TODO verify stateFile location has not changed since last build
    try {
      ObjectInputStream is =
          new ObjectInputStream(new BufferedInputStream(new FileInputStream(stateFile)));
      try {
        return (DefaultBuildContextState) is.readObject();
      } finally {
        try {
          is.close();
        } catch (IOException e) {
          // ignore secondary exceptions
        }
      }
    } catch (FileNotFoundException e) {
      // this is expected, ignore
    } catch (Exception e) {
      log.debug("Could not read build state file {}", stateFile, e);
    }
    return null;
  }

  private void storeState() throws IOException {
    final DefaultBuildContextState state =
        new DefaultBuildContextState(configuration, inputs, outputs, inputOutputs, outputInputs,
            inputIncludedInputs, inputRequirements, requirementInputs, outputCapabilities,
            inputAttributes, inputMessages);

    ObjectOutputStream os =
        new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(stateFile))) {
          {
            enableReplaceObject(true);
          }

          @Override
          protected Object replaceObject(Object obj) throws IOException {
            // TODO maybe don't serialize DefaultInput/DefaultOutput instances?
            if (obj == DefaultBuildContext.this) {
              return state;
            }
            return super.replaceObject(obj);
          }
        };
    try {
      os.writeObject(state);
    } finally {
      try {
        os.close();
      } catch (IOException e) {
        // ignore secondary exception
      }
    }
  }

  @Override
  public DefaultInput processInput(File inputFile) {
    if (inputs.containsKey(inputFile)) {
      // skip, inputFile has been processed already
      return null;
    }
    if (escalated) {
      return registerInput(inputFile);
    }
    final DefaultInput oldInput = oldState.getInputs().get(inputFile);
    if (oldInput == null || oldInput.isProcessingRequired()) {
      return registerInput(inputFile);
    }
    return null;
  }

  @Override
  public Iterable<DefaultInput> processInputs(FileSet fileSet) {
    // reminder to self: don't optimize prematurely

    Set<DefaultInput> result = new LinkedHashSet<DefaultInput>();

    for (File inputFile : fileSet) {

      // can return the same input twice, if called concurrently from multiple threads

      if (inputs.containsKey(inputFile)) {
        // skip, this inputFile has been processed already
        continue;
      }

      final DefaultInput oldInput = oldState.getInputs().get(inputFile);
      if (oldInput == null || oldInput.isProcessingRequired()) {
        // this is new or changed input file
        result.add(registerInput(inputFile));
      }
    }

    return result;
  }

  // low-level methods

  /**
   * @return
   * @throws IOException if a stale output file cannot be deleted.
   */
  public Iterable<DefaultOutput> deleteStaleOutputs() throws IOException {
    if (oldState == null) {
      return Collections.emptyList();
    }

    List<DefaultOutput> deleted = new ArrayList<DefaultOutput>();

    oldOutpus: for (Map.Entry<File, DefaultOutput> oldOutput : oldState.getOutputs().entrySet()) {
      final File outputFile = oldOutput.getKey();

      // keep if output file was registered during this build
      if (outputs.containsKey(outputFile)) {
        continue oldOutpus;
      }

      for (DefaultInput oldInput : oldOutput.getValue().getAssociatedInputs()) {
        final File inputFile = oldInput.getResource();

        if (FileState.isPresent(inputFile)) {
          // keep if inputFile is not registered during this build
          // keep if inputFile is registered and is associated with outputFile

          final DefaultInput input = inputs.get(inputFile);
          if (input == null || input.isAssociatedOutput(outputFile)) {
            continue oldOutpus;
          }
        }
      }

      if (outputFile.exists() && !outputFile.delete()) {
        throw new IOException("Could not delete file " + outputFile);
      }

      deleted.add(oldOutput.getValue());
    }
    return deleted;
  }

  private static <K, V> V putIfAbsent(ConcurrentMap<K, V> map, K key, V value) {
    // XXX do I do this right? need to check with the concurrency book
    map.putIfAbsent(key, value);
    return map.get(key);
  }

  @Override
  public DefaultOutput registerOutput(File outputFile) {
    outputFile = normalize(outputFile);

    DefaultOutput output = outputs.get(outputFile);
    if (output == null) {
      output = putIfAbsent(outputs, outputFile, new DefaultOutput(this, outputFile));
    }

    return output;
  }

  @Override
  public DefaultOutput getOldOutput(File outputFile) {
    return oldState.getOutputs().get(outputFile);
  }

  @Override
  public DefaultInput registerInput(File inputFile) {
    if (!FileState.isPresent(inputFile)) {
      throw new IllegalArgumentException("Input file does not exist or cannot be read " + inputFile);
    }

    inputFile = normalize(inputFile);

    DefaultInput result = inputs.get(inputFile);
    if (result == null) {
      result = putIfAbsent(inputs, inputFile, new DefaultInput(this, inputFile));
    }

    return result;
  }

  private File normalize(File file) {
    try {
      return file.getCanonicalFile();
    } catch (IOException e) {
      log.debug("Could not normalize file {}", file, e);
      return file.getAbsoluteFile();
    }
  }

  @Override
  public FileSetBuilder fileSetBuilder() {
    // TODO Auto-generated method stub
    return null;
  }

  // association management

  @Override
  public DefaultOutput associateOutput(DefaultInput input, File outputFile) {
    DefaultOutput output = registerOutput(outputFile);
    associate(input, output);
    return output;
  }

  @Override
  public void associate(DefaultInput input, DefaultOutput output) {
    File inputFile = input.getResource();
    Collection<DefaultOutput> outputs = inputOutputs.get(inputFile);
    if (outputs == null) {
      outputs = putIfAbsent(inputOutputs, inputFile, new LinkedHashSet<DefaultOutput>());
    }
    outputs.add(output); // XXX NOT THREAD SAFE
  }

  @Override
  public boolean isAssociatedOutput(DefaultInput input, File outputFile) {
    DefaultOutput output = outputs.get(outputFile);
    if (output == null) {
      return false;
    }
    Collection<DefaultOutput> outputs = inputOutputs.get(input.getResource());
    return outputs != null && outputs.contains(output);
  }

  @Override
  public Collection<DefaultInput> getAssociatedInputs(DefaultOutput output) {
    return Collections.unmodifiableCollection(outputInputs.get(output.getResource()));
  }

  @Override
  public void associateIncludedInput(DefaultInput input, File includedFile) {
    File inputFile = input.getResource();
    Collection<File> includedFiles = inputIncludedInputs.get(inputFile);
    if (includedFiles == null) {
      includedFiles = putIfAbsent(inputIncludedInputs, inputFile, new LinkedHashSet<File>());
    }
    includedFiles.add(includedFile); // XXX NOT THREAD SAFE
  }

  @Override
  public boolean isProcessingRequired(DefaultInput input) {
    return true;
  }

  // provided/required capability matching

  @Override
  public void addRequirement(DefaultInput input, String qualifier, String localName) {
    addRequirement(input, new QualifiedName(qualifier, localName));
  }

  private void addRequirement(DefaultInput input, QualifiedName requirement) {
    Collection<DefaultInput> inputs = requirementInputs.get(requirement);
    if (inputs == null) {
      inputs = putIfAbsent(requirementInputs, requirement, new LinkedHashSet<DefaultInput>());
    }
    inputs.add(input); // XXX NOT THREAD SAFE

    File inputFile = input.getResource();
    Collection<QualifiedName> requirements = inputRequirements.get(inputFile);
    if (requirements == null) {
      requirements = putIfAbsent(inputRequirements, inputFile, new LinkedHashSet<QualifiedName>());
    }
    requirements.add(requirement); // XXX NOT THREAD SAFE
  }

  @Override
  public void addCapability(DefaultOutput output, String qualifier, String localName) {
    File outputFile = output.getResource();
    Collection<QualifiedName> capabilities = outputCapabilities.get(outputFile);
    if (capabilities == null) {
      capabilities =
          putIfAbsent(outputCapabilities, outputFile, new LinkedHashSet<QualifiedName>());
    }
    capabilities.add(new QualifiedName(qualifier, localName)); // XXX NOT THREAD SAFE
  }

  @Override
  public Collection<String> getCapabilities(DefaultOutput output, String qualifier) {
    Collection<QualifiedName> capabilities = outputCapabilities.get(output.getResource());
    if (capabilities == null) {
      return Collections.emptyList();
    }
    Set<String> result = new LinkedHashSet<String>();
    for (QualifiedName capability : capabilities) {
      if (qualifier.equals(capability.getQualifier())) {
        result.add(capability.getLocalName());
      }
    }
    return result;
  }

  /**
   * Returns {@code Input}s with specified requirement. Inputs from the old state are automatically
   * registered for processing.
   */
  public Iterable<DefaultInput> getDependentInputs(String qualifier, String localName) {
    Map<File, DefaultInput> result = new LinkedHashMap<File, DefaultInput>();

    Collection<DefaultInput> inputs =
        requirementInputs.get(new QualifiedName(qualifier, localName));
    if (inputs != null) {
      for (DefaultInput input : inputs) {
        result.put(input.getResource(), input);
      }
    }

    if (oldState != null) {
      for (DefaultInput oldInput : oldState.getDependentInputs(qualifier, localName)) {
        File inputFile = oldInput.getResource();
        if (!result.containsKey(inputFile)) {
          result.put(inputFile, registerInput(inputFile));
        }
      }
    }

    return result.values();
  }

  // simple key/value pairs

  @Override
  public <T extends Serializable> void setValue(DefaultInput input, String key, T value) {
    File inputFile = input.getResource();
    Map<String, Serializable> attributes = inputAttributes.get(inputFile);
    if (attributes == null) {
      attributes =
          putIfAbsent(inputAttributes, inputFile, new LinkedHashMap<String, Serializable>());
    }
    attributes.put(key, value); // XXX NOT THREAD SAFE
  }

  @Override
  public <T extends Serializable> T getValue(DefaultInput input, String key, Class<T> clazz) {
    Map<String, Serializable> attributes = inputAttributes.get(input.getResource());
    return attributes != null ? clazz.cast(attributes.get(key)) : null;
  }

  // messages

  @Override
  public void addMessage(DefaultInput input, int line, int column, String message, int severity,
      Throwable cause) {
    File inputFile = input.getResource();
    Collection<Message> messages = this.inputMessages.get(inputFile);
    if (messages == null) {
      messages = putIfAbsent(inputMessages, inputFile, new LinkedHashSet<Message>());
    }
    messages.add(new Message(line, column, message, severity, cause)); // XXX NOT THREAD SAFE
    failed = failed || severity == SEVERITY_ERROR;

    // echo message
    logMessage(input, line, column, message, severity, cause);
  }

  public void commit() throws BuildFailureException, IOException {
    deleteStaleOutputs();

    // copy relevant parts of the old state

    if (oldState != null) {
      for (DefaultInput oldInput : oldState.getInputs().values()) {
        File inputFile = oldInput.getResource();
        if (FileState.isPresent(inputFile) && !inputs.containsKey(inputFile)) {
          DefaultInput input = registerInput(inputFile);

          // deep(!) copy associated outputs
          for (DefaultOutput oldOutput : oldState.getAssociatedOutputs(inputFile)) {
            File outputFile = oldOutput.getResource();

            associate(input, registerOutput(outputFile));

            Collection<QualifiedName> capabilities = oldState.getOutputCapabilities(outputFile);
            if (capabilities != null) {
              outputCapabilities.put(outputFile, new LinkedHashSet<QualifiedName>(capabilities));
            }
          }

          // copy associated included inputs
          Collection<File> includedInputs = oldState.getInputIncludedInputs(inputFile);
          if (includedInputs != null) {
            inputIncludedInputs.put(inputFile, new LinkedHashSet<File>(includedInputs));
          }

          // copy requirements
          Collection<QualifiedName> requirements = oldState.getInputRequirements(inputFile);
          if (requirements != null) {
            for (QualifiedName requirement : requirements) {
              addRequirement(input, requirement);
            }
          }

          // copy messages
          Collection<Message> messages = oldState.getInputMessages(inputFile);
          if (messages != null) {
            inputMessages.put(inputFile, new LinkedHashSet<Message>(messages));

            // replay old messages
            for (Message message : messages) {
              logMessage(input, message.line, message.column, message.message, message.severity,
                  message.cause);
              failed = failed || message.severity == SEVERITY_ERROR;
            }
          }
        }
      }
    }

    storeState();

    if (failed) {
      throw newBuildFailureException();
    }
  }

  protected abstract void logMessage(DefaultInput input, int line, int column, String message,
      int severity, Throwable cause);

  protected abstract BuildFailureException newBuildFailureException();
}
