package io.takari.builder.testing;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import io.takari.builder.enforcer.ComposableSecurityManagerPolicy;
import io.takari.builder.enforcer.Policy;

/**
 * The BuilderRuntime Rules allows setup and tear down of Builder context during Maven unit test.
 */
public class BuilderRuntime implements TestRule {

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        enterTestScope();
        try {
          base.evaluate();
        } finally {
          leaveTestScope();
        }
      }
    };
  }

  //
  //
  //

  public static void enterTestScope() {
    privileged.set(TRUE);
    ComposableSecurityManagerPolicy.setSystemSecurityManager();
    ComposableSecurityManagerPolicy.setDefaultPolicy(BuilderRuntime.POLICY);
  }

  public static void leaveTestScope() {
    privileged.set(FALSE);
    ComposableSecurityManagerPolicy.setDefaultPolicy(null);
    ComposableSecurityManagerPolicy.removeSystemSecurityManager();
  }

  private static final ThreadLocal<Boolean> privileged = ThreadLocal.withInitial(() -> FALSE);

  private static final Policy POLICY = new Policy() {

    @Override
    public void checkWrite(String file) {
      checkSecuredTestExecutionContext();
    }

    @Override
    public void checkSocketPermission() {
      checkSecuredTestExecutionContext();
    }

    @Override
    public void checkRead(String file) {
      checkSecuredTestExecutionContext();
    }

    @Override
    public void checkPropertyPermission(String action, String name) {
      checkSecuredTestExecutionContext();
    }

    @Override
    public void checkExec(String cmd) {
      checkSecuredTestExecutionContext();
    }

    private void checkSecuredTestExecutionContext() {
      if (privileged.get() == FALSE) {
        throw new SecurityException(
            "Cannot access system resources without builder context at this thread.");
      }
    }
  };

}
