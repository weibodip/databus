package com.weibo.dip.databus.source;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.weibo.dip.databus.core.Configuration;
import com.weibo.dip.databus.core.Constants;
import com.weibo.dip.databus.core.Message;
import com.weibo.dip.databus.core.Source;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.weibo.dip.databus.source.FileSourceConfConstants.*;

/** Created by jianhong1 on 2019-07-05. */
public class FileSource extends Source {
  private static final Logger LOGGER = LoggerFactory.getLogger(FileSource.class);
  private static final String FILE_STATUS_PATTERN = "(\\S+) (\\S+) (\\S+)";
  private final AtomicBoolean fileReaderClosed = new AtomicBoolean(false);
  private LinkedBlockingQueue<File> fileQueue = new LinkedBlockingQueue<>();
  private ScheduledExecutorService fileScanner;
  private ExecutorService fileReader;
  private ScheduledExecutorService offsetRecorder;
  private ConcurrentHashMap<String, FileStatus> fileStatusMap = new ConcurrentHashMap<>();
  private String category;
  private int threadNumber;
  private String includePattern;
  private int scanInterval;
  private int flushInterval;
  private int flushInitDelay;
  private int retention;
  private int bufferSize;
  private int lineBufferSize;
  private String readOrder;
  private boolean deleteAfterRead;
  private File targetDirectory;
  private File offsetFile;
  private Meter meter;
  private Comparator<File> descComparator =
      (File o1, File o2) -> (int) (o2.lastModified() - o1.lastModified());
  private Comparator<File> ascComparator =
      (File o1, File o2) -> (int) (o1.lastModified() - o2.lastModified());

  @Override
  public void setConf(Configuration conf) throws Exception {
    name = conf.get(Constants.PIPELINE_NAME) + Constants.HYPHEN + this.getClass().getSimpleName();

    String fileDirectory = conf.get(FILE_DIRECTORY);
    Preconditions.checkState(
        StringUtils.isNotEmpty(fileDirectory),
        String.format("%s %s must be specified", name, FILE_DIRECTORY));
    LOGGER.info("Property: {}={}", FILE_DIRECTORY, fileDirectory);

    category = conf.get(CATEGORY);
    Preconditions.checkState(
        StringUtils.isNotEmpty(category), String.format("%s %s must be specified", name, CATEGORY));
    LOGGER.info("Property: {}={}", CATEGORY, category);

    targetDirectory = new File(fileDirectory);
    if (targetDirectory.exists() && targetDirectory.isDirectory()) {
      // 若category offset文件不存在，则创建
      offsetFile = new File(String.format("%s/%s.offset", fileDirectory, category));

      if (offsetFile.createNewFile()) {
        LOGGER.info("{} does not exist and was successfully created", offsetFile);
      } else {
        LOGGER.info("{} already exists", offsetFile);
      }
    } else {
      throw new Exception(fileDirectory + " is not exist or not directory!");
    }

    threadNumber = conf.getInteger(THREAD_NUMBER, DEFAULT_THREAD_NUMBER);
    LOGGER.info("Property: {}={}", THREAD_NUMBER, threadNumber);

    includePattern = conf.getString(INCLUDE_PATTERN, DEFAULT_INCLUDE_PATTERN);
    LOGGER.info("Property: {}={}", INCLUDE_PATTERN, includePattern);

    scanInterval = conf.getInteger(SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL);
    LOGGER.info("Property: {}={}", SCAN_INTERVAL, scanInterval);

    flushInitDelay = conf.getInteger(FLUSH_INIT_DELAY, DEFAULT_FLUSH_INIT_DELAY);
    LOGGER.info("Property: {}={}", FLUSH_INIT_DELAY, flushInitDelay);

    flushInterval = conf.getInteger(FLUSH_INTERVAL, DEFAULT_FLUSH_INTERVAL);
    LOGGER.info("Property: {}={}", FLUSH_INTERVAL, flushInterval);

    retention = conf.getInteger(RETENTION, DEFAULT_RETENTION);
    LOGGER.info("Property: {}={}", RETENTION, retention);

    readOrder = conf.getString(READ_ORDER, DEFAULT_READ_ORDER);
    if (!"desc".equals(readOrder) && !"asc".equals(readOrder)) {
      throw new Exception(READ_ORDER + " should be desc or asc");
    }
    LOGGER.info("Property: {}={}", READ_ORDER, readOrder);

    bufferSize = conf.getInteger(BUFFER_SIZE, DEFAULT_BUFFER_SIZE);
    LOGGER.info("Property: {}={}", BUFFER_SIZE, bufferSize);

    lineBufferSize = conf.getInteger(LINE_BUFFER_SIZE, DEFAULT_LINE_BUFFER_SIZE);
    LOGGER.info("Property: {}={}", LINE_BUFFER_SIZE, lineBufferSize);

    deleteAfterRead = conf.getBoolean(DELETE_AFTER_READ, DEFAULT_DELETE_AFTER_READ);
    LOGGER.info("Property: {}={}", DELETE_AFTER_READ, deleteAfterRead);

    metric.gauge(MetricRegistry.name(name, "fileQueue", "size"), () -> fileQueue.size());
    meter = metric.meter(MetricRegistry.name(name, "readLines", "tps"));
  }

  @Override
  public void start() {
    LOGGER.info("{} starting...", name);

    // 加载file offset信息到map
    loadOffsetFile();

    // 定时扫描目录下的未读文件，放到队列中
    fileScanner =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("fileScanner-pool-%d").build());
    fileScanner.scheduleWithFixedDelay(
        new FileScannerRunnable(fileQueue), 0, scanInterval, TimeUnit.SECONDS);

    // 从队列中取出文件，把文件内容读到内存，一个文件由一个线程处理
    fileReader =
        Executors.newFixedThreadPool(
            threadNumber, new ThreadFactoryBuilder().setNameFormat("fileReader-pool-%d").build());
    for (int index = 0; index < threadNumber; index++) {
      fileReader.execute(new FileReaderRunnable(fileQueue));
    }

    // 把文件当前位置offset信息定时刷到磁盘
    offsetRecorder =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("offsetRecorder-pool-%d").build());
    offsetRecorder.scheduleWithFixedDelay(
        new OffsetRecorderRunnable(), flushInitDelay, flushInterval, TimeUnit.SECONDS);

    LOGGER.info("{} started", name);
  }

  private void loadOffsetFile() {
    BufferedReader in = null;
    Pattern pattern = Pattern.compile(FILE_STATUS_PATTERN);
    ArrayList<File> files = new ArrayList<>();
    Comparator<File> comparator = ascComparator;

    try {
      in = new BufferedReader(new InputStreamReader(new FileInputStream(offsetFile)));

      String line;
      while ((line = in.readLine()) != null) {
        Matcher matcher = pattern.matcher(line);

        if (matcher.find()) {
          FileStatus fileStatus = new FileStatus();

          String filePath = matcher.group(1);
          fileStatus.setPath(filePath);
          fileStatus.setOffset(Long.valueOf(matcher.group(2)));

          boolean isCompleted = Boolean.parseBoolean(matcher.group(3));
          fileStatus.setCompleted(isCompleted);
          fileStatusMap.put(filePath, fileStatus);

          // 筛选未读完的文件
          if (!isCompleted) {
            files.add(new File(filePath));
          }
        } else {
          LOGGER.error("'{}' format invalid", line);
        }
      }
      LOGGER.info("initial map size: {}", fileStatusMap.size());

      // 文件排序
      if ("desc".equals(readOrder)) {
        comparator = descComparator;
      }
      files.sort(comparator);

      // 文件入队
      for (File file : files) {
        try {
          fileQueue.put(file);
          LOGGER.info("{} enqueue", file);
        } catch (InterruptedException e) {
          LOGGER.error("{} enqueue, but interrupted", file);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("read offsetFile error: " + ExceptionUtils.getStackTrace(e));
    } finally {
      try {
        if (in != null) {
          in.close();
        }
      } catch (IOException e) {
        LOGGER.error("close offsetFile BufferedReader error: {}", ExceptionUtils.getStackTrace(e));
      }
    }
  }

  @Override
  public void stop() {
    LOGGER.info("{} stopping...", name);

    fileScanner.shutdown();
    try {
      while (!fileScanner.awaitTermination(THREAD_POOL_AWAIT_TIMEOUT, TimeUnit.SECONDS)) {
        LOGGER.info("{} fileScanner await termination", name);
      }
    } catch (InterruptedException e) {
      LOGGER.warn("{} fileScanner await termination, but interrupted", name);
    }
    LOGGER.info("{} fileScanner stopped", name);

    fileReaderClosed.set(true);
    fileReader.shutdown();
    try {
      while (!fileReader.awaitTermination(THREAD_POOL_AWAIT_TIMEOUT, TimeUnit.SECONDS)) {
        LOGGER.info("{} fileReader await termination", name);
      }
    } catch (InterruptedException e) {
      LOGGER.warn("{} fileReader await termination, but interrupted", name);
    }
    LOGGER.info("{} fileReader stopped", name);

    offsetRecorder.shutdown();
    try {
      while (!offsetRecorder.awaitTermination(THREAD_POOL_AWAIT_TIMEOUT, TimeUnit.SECONDS)) {
        LOGGER.info("{} offsetRecorder await termination", name);
      }
    } catch (InterruptedException e) {
      LOGGER.warn("{} offsetRecorder await termination, but interrupted", name);
    }
    LOGGER.info("{} offsetRecorder stopped", name);

    // 确保最新的fileStatusMap内容刷到磁盘
    new OffsetRecorderRunnable().run();

    metric.remove(MetricRegistry.name(name, "fileQueue", "size"));
    metric.remove(MetricRegistry.name(name, "readLines", "tps"));

    LOGGER.info("{} stopped", name);
  }

  private class FileScannerRunnable implements Runnable {
    LinkedBlockingQueue<File> fileQueue;
    Comparator<File> comparator = ascComparator;

    private FileScannerRunnable(LinkedBlockingQueue<File> fileQueue) {
      this.fileQueue = fileQueue;
    }

    @Override
    public void run() {
      try {
        HashSet<String> dirSet = new HashSet<>();
        ArrayList<File> files = new ArrayList<>();

        for (File item : targetDirectory.listFiles()) {
          // 筛选符合正则的文件
          if (item.isFile() && item.getName().matches(includePattern)) {

            // 删除目录下过期文件
            if (System.currentTimeMillis() - item.lastModified() > retention * 1000) {
              if (item.delete()) {
                LOGGER.info("deleted expired file {}", item);
              } else {
                LOGGER.error("delete expired file {} failed", item);
              }
              continue;
            }

            String filePath = item.getPath();
            dirSet.add(filePath);

            // 筛选未读文件
            if (!fileStatusMap.containsKey(filePath)) {
              files.add(item);
            }
          }
        }

        // 文件排序
        if ("desc".equals(readOrder)) {
          comparator = descComparator;
        }
        files.sort(comparator);

        // 未读的文件入队
        for (File item : files) {
          try {
            fileQueue.put(item);
            LOGGER.info("{} enqueue", item);

            String filePath = item.getPath();
            fileStatusMap.put(filePath, new FileStatus(filePath));
          } catch (InterruptedException e) {
            LOGGER.error("{} enqueue, but interrupted", item);
          }
        }
        LOGGER.info("queue size: {}", fileQueue.size());

        // 删除map中过期文件
        for (Map.Entry<String, FileStatus> entry : fileStatusMap.entrySet()) {

          if (!dirSet.contains(entry.getKey())) {
            fileStatusMap.remove(entry.getKey());
            LOGGER.info("removed expired file {} from map", entry.getKey());
          }
        }
        LOGGER.info("map size: {}", fileStatusMap.size());

      } catch (Exception e) {
        LOGGER.error("read offsetFile error: {}", ExceptionUtils.getStackTrace(e));
      }
    }
  }

  private class FileReaderRunnable implements Runnable {
    LinkedBlockingQueue<File> fileQueue;

    private FileReaderRunnable(LinkedBlockingQueue<File> fileQueue) {
      this.fileQueue = fileQueue;
    }

    @Override
    public void run() {
      while (!fileReaderClosed.get()) {
        RandomAccessFile raf = null;
        FileChannel fc = null;

        try {
          File item = fileQueue.poll(1, TimeUnit.SECONDS);

          if (item == null) {
            continue;
          } else if (!item.exists()) {
            LOGGER.error("{} not exists, may be deleted before read", item);
            continue;
          } else if (!item.isFile()) {
            LOGGER.error("{} not file", item);
            continue;
          }
          LOGGER.info("{} dequeue", item);

          String filePath = item.getPath();
          // 是否可以用锁来替换？？
          if (fileStatusMap.get(filePath) == null) {
            LOGGER.warn("{} should in map, but not, so input", filePath);
            fileStatusMap.putIfAbsent(filePath, new FileStatus(filePath));
          }
          FileStatus fileStatus = fileStatusMap.get(filePath);

          raf = new RandomAccessFile(filePath, "r");
          fc = raf.getChannel();

          long currPos = fileStatus.getOffset();
          fc.position(currPos);
          LOGGER.debug("origin position: {}", currPos);

          ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
          ByteBuffer lineBuffer = ByteBuffer.allocate(lineBufferSize);

          int bytesRead = fc.read(buffer);
          while (bytesRead != -1 && !fileReaderClosed.get()) {
            currPos = fc.position() - bytesRead;

            buffer.flip();
            while (buffer.hasRemaining()) {
              byte b = buffer.get();
              currPos++;
              if (b == '\n' || b == '\r') {
                sendLine(lineBuffer);
                fileStatus.setOffset(currPos);
              } else {
                // 若空间不够则扩容
                if (!lineBuffer.hasRemaining()) {
                  lineBuffer = reAllocate(lineBuffer);
                }
                lineBuffer.put(b);
              }
            }
            buffer.clear();

            bytesRead = fc.read(buffer);
          }

          // 处理最后一行
          if (lineBuffer.position() > 0 && !fileReaderClosed.get()) {
            sendLine(lineBuffer);
            fileStatus.setOffset(fc.position());
            LOGGER.debug("fileChannel position: {}", fc.position());
          }

          if (!fileReaderClosed.get()) {
            fileStatus.setCompleted(true);
            LOGGER.info("read {} completed", filePath);

            if (deleteAfterRead) {
              if (item.delete()) {
                LOGGER.info("read completed and delete {} success", item);
              } else {
                LOGGER.error("read completed but delete {} failed", item);
              }
            }
          }
        } catch (Exception e) {
          LOGGER.error("read logFile error: {}", ExceptionUtils.getStackTrace(e));
        } finally {
          try {
            if (fc != null) {
              fc.close();
            }
            if (raf != null) {
              raf.close();
            }
          } catch (IOException e) {
            LOGGER.error("close RandomAccessFile error: {}", ExceptionUtils.getStackTrace(e));
          }
        }
      }
    }

    private void sendLine(ByteBuffer lineBuffer) {
      lineBuffer.flip();

      String line = Charset.forName("UTF-8").decode(lineBuffer).toString();
      deliver(new Message(category, line));
      meter.mark();

      lineBuffer.clear();
    }

    private ByteBuffer reAllocate(ByteBuffer buffer) {
      int capacity = buffer.capacity();
      byte[] newBuffer = new byte[capacity * 2];
      System.arraycopy(buffer.array(), 0, newBuffer, 0, capacity);
      return (ByteBuffer) ByteBuffer.wrap(newBuffer).position(capacity);
    }
  }

  private class OffsetRecorderRunnable implements Runnable {

    @Override
    public void run() {
      BufferedWriter out = null;

      try {
        StringBuilder sb = new StringBuilder();
        for (FileStatus fileStatus : fileStatusMap.values()) {
          sb.append(fileStatus).append("\n");
        }

        if (sb.length() > 0) {
          sb.deleteCharAt(sb.length() - 1);
        }

        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(offsetFile)));

        String str = sb.toString();
        out.write(str);
        LOGGER.info("flush {} offsetRecords to disk", fileStatusMap.size());
      } catch (Exception e) {
        LOGGER.error("write offset to file error: {}", ExceptionUtils.getStackTrace(e));
      } finally {
        try {
          if (out != null) {
            out.close();
          }
        } catch (IOException e) {
          LOGGER.error("close BufferedWriter error: {}", ExceptionUtils.getStackTrace(e));
        }
      }
    }
  }
}
