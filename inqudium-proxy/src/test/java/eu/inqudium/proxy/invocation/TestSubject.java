package eu.inqudium.proxy.invocation;

import java.io.IOException;

/**
 * Shared test fixture for the {@link MethodInvoker} tests. The
 * methods cover a few representative shapes: no-arg void, primitive
 * arguments, reference arguments, primitive return, reference return,
 * and three throwing methods (runtime, checked, error) for the
 * exception-propagation tests.
 */
final class TestSubject {

    boolean doNothingCalled;

    public void doNothing() {
        doNothingCalled = true;
    }

    public String greet(String name) {
        return "hello " + name;
    }

    public int sum(int a, int b) {
        return a + b;
    }

    public void throwRuntime() {
        throw new IllegalStateException("runtime boom");
    }

    public void throwChecked() throws IOException {
        throw new IOException("checked boom");
    }

    public void throwError() {
        throw new AssertionError("error boom");
    }
}
