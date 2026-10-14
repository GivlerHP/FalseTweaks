import com.sun.tools.attach.VirtualMachine;
import java.io.PrintWriter;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import javax.management.remote.*;

/** Samples heap, non-heap, direct/mapped buffers, and GC from a Java 8 process. */
public final class MemorySampler {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("PID seconds output.tsv");
        int seconds = Integer.parseInt(args[1]);
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        String address;
        try {
            address = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            if (address == null) address = vm.startLocalManagementAgent();
        } finally { vm.detach(); }
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(args[2])))) {
            MemoryMXBean memory = ManagementFactory.newPlatformMXBeanProxy(connector.getMBeanServerConnection(),
                ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
            List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(
                connector.getMBeanServerConnection(), BufferPoolMXBean.class);
            List<GarbageCollectorMXBean> collectors = ManagementFactory.getPlatformMXBeans(
                connector.getMBeanServerConnection(), GarbageCollectorMXBean.class);
            out.println("elapsed_s\theap_used\theap_committed\theap_max\tnonheap_used\t"
                + "direct_count\tdirect_capacity\tdirect_used\tmapped_count\tmapped_capacity\tmapped_used\tgc_count\tgc_time_ms");
            for (int s = 0; s <= seconds; s++) {
                MemoryUsage heap = memory.getHeapMemoryUsage(), nonheap = memory.getNonHeapMemoryUsage();
                long directCount=0, directCapacity=0, directUsed=0, mappedCount=0, mappedCapacity=0, mappedUsed=0;
                for (BufferPoolMXBean pool : pools) {
                    if (pool.getName().equals("direct")) {
                        directCount=pool.getCount(); directCapacity=pool.getTotalCapacity(); directUsed=pool.getMemoryUsed();
                    } else if (pool.getName().equals("mapped")) {
                        mappedCount=pool.getCount(); mappedCapacity=pool.getTotalCapacity(); mappedUsed=pool.getMemoryUsed();
                    }
                }
                long gcCount=0, gcTime=0;
                for (GarbageCollectorMXBean collector : collectors) {
                    gcCount += Math.max(0, collector.getCollectionCount());
                    gcTime += Math.max(0, collector.getCollectionTime());
                }
                out.printf(Locale.ROOT, "%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d%n",
                    s, heap.getUsed(), heap.getCommitted(), heap.getMax(), nonheap.getUsed(),
                    directCount, directCapacity, directUsed, mappedCount, mappedCapacity, mappedUsed, gcCount, gcTime);
                out.flush();
                if (s < seconds) Thread.sleep(1000);
            }
        }
    }
}
