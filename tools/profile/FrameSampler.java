import com.sun.tools.attach.VirtualMachine;
import com.sun.management.GarbageCollectionNotificationInfo;
import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.remote.*;

/** External wall-clock sampler for Java 8. No Minecraft mod or launch argument is required. */
public final class FrameSampler {
    private static PrintWriter writer(Path dir, String name) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8));
    }

    private static boolean selected(String name) {
        return name.equals("Client thread") || name.equals("Server thread")
                || name.startsWith("FT Neodymium") || name.startsWith("DH-");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("PID durationSeconds outputDirectory");
        int seconds = Integer.parseInt(args[1]);
        if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("duration must be 1..600 seconds");
        Path dir = Paths.get(args[2]);
        Files.createDirectories(dir);
        String address;
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try {
            address = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            if (address == null) address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        Map<String, Integer> stacks = new HashMap<String, Integer>();
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
             PrintWriter samples = writer(dir, "samples.tsv");
             PrintWriter gc = writer(dir, "gc.tsv");
             PrintWriter heap = writer(dir, "heap.tsv");
             PrintWriter summary = writer(dir, "summary.txt")) {
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            ThreadMXBean threads = ManagementFactory.newPlatformMXBeanProxy(connection,
                    ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
            RuntimeMXBean runtime = ManagementFactory.newPlatformMXBeanProxy(connection,
                    ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
            MemoryMXBean memory = ManagementFactory.newPlatformMXBeanProxy(connection,
                    ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
            final long baseUptime = runtime.getUptime();
            summary.println("JVM: " + runtime.getVmName() + " " + runtime.getVmVersion());
            summary.println("Started: " + new Date() + "; target uptime: " + baseUptime + " ms");
            summary.println("50 ms wall-clock stack sampling; percentages are sample shares, not CPU percentages.");
            summary.println("This is not a frame-time capture. JMX request delays can include safepoints, scheduling and transport.");
            summary.println("GC duration is collector event duration; a concurrent collection is not necessarily a stop-the-world pause.");
            gc.println("relative_start_ms\tduration_ms\tcollector\taction\tcause");
            for (ObjectName name : connection.queryNames(new ObjectName("java.lang:type=GarbageCollector,*"), null)) {
                connection.addNotificationListener(name, (notification, handback) -> {
                    if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) return;
                    GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                    synchronized (gc) {
                        gc.println((info.getGcInfo().getStartTime() - baseUptime) + "\t" + info.getGcInfo().getDuration()
                                + "\t" + info.getGcName() + "\t" + info.getGcAction() + "\t" + info.getGcCause());
                        gc.flush();
                    }
                }, null, null);
            }
            samples.println("elapsed_ms\trequest_ms\tclient_cpu_ns\tthread\tstate\tstack");
            heap.println("elapsed_ms\tused_bytes\tcommitted_bytes\tmax_bytes");
            long start = System.nanoTime();
            long nextRefresh = 0;
            long[] ids = new long[0];
            long clientId = -1;
            long count = 0;
            System.out.println("Recording " + seconds + " seconds into " + dir.toAbsolutePath());
            while ((System.nanoTime() - start) / 1000000000L < seconds) {
                long sampleStart = System.nanoTime();
                long elapsed = (sampleStart - start) / 1000000L;
                if (elapsed >= nextRefresh) {
                    List<Long> matching = new ArrayList<Long>();
                    for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
                        if (info != null && selected(info.getThreadName())) {
                            matching.add(info.getThreadId());
                            if (info.getThreadName().equals("Client thread")) clientId = info.getThreadId();
                        }
                    }
                    ids = new long[matching.size()];
                    for (int i = 0; i < ids.length; i++) ids[i] = matching.get(i);
                    MemoryUsage usage = memory.getHeapMemoryUsage();
                    heap.println(elapsed + "\t" + usage.getUsed() + "\t" + usage.getCommitted() + "\t" + usage.getMax());
                    heap.flush();
                    samples.flush();
                    nextRefresh = elapsed + 1000;
                }
                long requestStart = System.nanoTime();
                ThreadInfo[] infos = threads.getThreadInfo(ids, 64);
                long cpu = clientId < 0 || !threads.isThreadCpuTimeSupported() ? -1 : threads.getThreadCpuTime(clientId);
                double requestMs = (System.nanoTime() - requestStart) / 1000000.0;
                for (ThreadInfo info : infos) {
                    if (info == null) continue;
                    StringBuilder stack = new StringBuilder();
                    for (StackTraceElement frame : info.getStackTrace()) {
                        if (stack.length() > 0) stack.append(" <- ");
                        stack.append(frame);
                    }
                    String key = info.getThreadName() + "\t" + info.getThreadState() + "\t" + stack;
                    stacks.put(key, stacks.getOrDefault(key, 0) + 1);
                    samples.printf(Locale.ROOT, "%d\t%.3f\t%d\t%s%n", elapsed, requestMs, cpu, key);
                }
                count++;
                long remaining = 50000000L - (System.nanoTime() - sampleStart);
                if (remaining > 0) Thread.sleep(remaining / 1000000L, (int) (remaining % 1000000L));
            }
            summary.println("Sampling rounds: " + count);
            if (clientId < 0) summary.println("WARNING: Minecraft Client thread was not found. Check the selected PID.");
            List<Map.Entry<String, Integer>> ranked = new ArrayList<Map.Entry<String, Integer>>(stacks.entrySet());
            ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Integer> entry : ranked) summary.println(entry.getValue() + "\t" + entry.getKey());
        }
        System.out.println("Recording complete.");
    }
}
