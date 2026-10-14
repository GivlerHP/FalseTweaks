/** Small disposable JVM for testing attachment and GC notifications without Minecraft. */
public final class SamplerProbe {
    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("Client thread");
        for (int i = 0; i < 150; i++) {
            if (i % 10 == 0) System.gc();
            Thread.sleep(100);
        }
    }
}
