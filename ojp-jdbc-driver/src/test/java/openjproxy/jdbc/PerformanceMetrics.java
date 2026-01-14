package openjproxy.jdbc;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for calculating performance metrics including percentile latencies,
 * throughput, and JVM statistics.
 */
public class PerformanceMetrics {
    
    /**
     * Calculates percentile value from a list of sorted values.
     * 
     * @param sortedValues List of sorted values (in ascending order)
     * @param percentile Percentile to calculate (e.g., 50 for median, 95 for p95, 99 for p99)
     * @return The percentile value
     */
    public static double calculatePercentile(List<Long> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0.0;
        }
        
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException("Percentile must be between 0 and 100");
        }
        
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
        index = Math.max(0, Math.min(index, sortedValues.size() - 1));
        
        return sortedValues.get(index);
    }
    
    /**
     * Calculates the maximum value from a list.
     * 
     * @param values List of values
     * @return The maximum value
     */
    public static long calculateMax(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        return Collections.max(values);
    }
    
    /**
     * Calculates throughput (operations per second).
     * 
     * @param totalOperations Total number of operations performed
     * @param totalTimeMs Total time in milliseconds
     * @return Throughput in operations per second
     */
    public static double calculateThroughput(int totalOperations, long totalTimeMs) {
        if (totalTimeMs <= 0) {
            return 0.0;
        }
        return (totalOperations * 1000.0) / totalTimeMs;
    }
    
    /**
     * Collects JVM statistics including GC pause time, heap usage, and CPU usage.
     * 
     * @return JvmStatistics object containing the collected metrics
     */
    public static JvmStatistics collectJvmStatistics() {
        JvmStatistics stats = new JvmStatistics();
        
        // Collect GC statistics
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalGcTime = 0;
        long totalGcCount = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            totalGcTime += gcBean.getCollectionTime();
            totalGcCount += gcBean.getCollectionCount();
        }
        stats.gcPauseTotalMs = totalGcTime;
        stats.gcCount = totalGcCount;
        
        // Collect memory statistics
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        stats.heapUsedMb = memoryBean.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
        stats.heapMaxMb = memoryBean.getHeapMemoryUsage().getMax() / (1024.0 * 1024.0);
        stats.heapCommittedMb = memoryBean.getHeapMemoryUsage().getCommitted() / (1024.0 * 1024.0);
        
        // Collect CPU usage (if available)
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            stats.processCpuLoad = sunOsBean.getProcessCpuLoad() * 100.0; // Convert to percentage
            stats.systemCpuLoad = sunOsBean.getSystemCpuLoad() * 100.0;
        }
        
        return stats;
    }
    
    /**
     * Container class for JVM statistics.
     */
    public static class JvmStatistics {
        public long gcPauseTotalMs;
        public long gcCount;
        public double heapUsedMb;
        public double heapMaxMb;
        public double heapCommittedMb;
        public double processCpuLoad;
        public double systemCpuLoad;
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("GC Pause Total: ").append(gcPauseTotalMs).append(" ms\n");
            sb.append("GC Count: ").append(gcCount).append("\n");
            sb.append(String.format("Heap Usage: %.2f MB / %.2f MB (%.2f MB committed)\n", 
                heapUsedMb, heapMaxMb, heapCommittedMb));
            if (processCpuLoad >= 0) {
                sb.append(String.format("Process CPU Load: %.2f%%\n", processCpuLoad));
            }
            if (systemCpuLoad >= 0) {
                sb.append(String.format("System CPU Load: %.2f%%\n", systemCpuLoad));
            }
            return sb.toString();
        }
    }
    
    /**
     * Calculates and formats a complete performance report.
     * 
     * @param queryDurations List of query durations in nanoseconds
     * @param totalQueries Total number of queries executed
     * @param totalTimeMs Total test duration in milliseconds
     * @return Formatted performance metrics report
     */
    public static String generatePerformanceReport(List<Long> queryDurations, int totalQueries, long totalTimeMs) {
        StringBuilder report = new StringBuilder();
        
        if (queryDurations.isEmpty()) {
            report.append("No query data available\n");
            return report.toString();
        }
        
        // Sort durations for percentile calculation
        List<Long> sortedDurations = new ArrayList<>(queryDurations);
        Collections.sort(sortedDurations);
        
        // Calculate metrics
        double avgQueryNs = sortedDurations.stream().mapToLong(Long::longValue).average().orElse(0);
        double avgQueryMs = avgQueryNs / 1_000_000.0;
        
        double p50Ns = calculatePercentile(sortedDurations, 50);
        double p50Ms = p50Ns / 1_000_000.0;
        
        double p95Ns = calculatePercentile(sortedDurations, 95);
        double p95Ms = p95Ns / 1_000_000.0;
        
        double p99Ns = calculatePercentile(sortedDurations, 99);
        double p99Ms = p99Ns / 1_000_000.0;
        
        long maxNs = calculateMax(sortedDurations);
        double maxMs = maxNs / 1_000_000.0;
        
        double throughput = calculateThroughput(totalQueries, totalTimeMs);
        
        // Format report
        report.append("\n=== LATENCY METRICS ===\n");
        report.append(String.format("Average latency: %.3f ms\n", avgQueryMs));
        report.append(String.format("P50 latency: %.3f ms\n", p50Ms));
        report.append(String.format("P95 latency: %.3f ms\n", p95Ms));
        report.append(String.format("P99 latency: %.3f ms\n", p99Ms));
        report.append(String.format("Max latency: %.3f ms\n", maxMs));
        
        report.append("\n=== THROUGHPUT ===\n");
        report.append(String.format("Throughput: %.2f queries/sec\n", throughput));
        
        return report.toString();
    }
}
