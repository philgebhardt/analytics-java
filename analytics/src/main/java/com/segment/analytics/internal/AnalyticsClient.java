package com.segment.analytics.internal;

import com.google.common.collect.ImmutableMap;
import com.segment.analytics.Log;
import com.segment.analytics.internal.http.SegmentService;
import com.segment.analytics.internal.http.UploadResponse;
import com.segment.analytics.messages.Message;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import retrofit.RetrofitError;

public class AnalyticsClient {
  private static final Map<String, Object> CONTEXT;

  static {
    ImmutableMap<String, String> library =
        ImmutableMap.of("name", "analytics-java", "version", AnalyticsVersion.get());
    CONTEXT = ImmutableMap.<String, Object>of("library", library);
  }

  private final BlockingQueue<Message> messageQueue;
  private final SegmentService service;
  private final int size;
  private final Log log;

  public AnalyticsClient(BlockingQueue<Message> messageQueue, SegmentService service, int size,
      Log log) {
    this.messageQueue = messageQueue;
    this.service = service;
    this.size = size;
    this.log = log;

    new Worker().start();
  }

  public void enqueue(Message message) {
    messageQueue.add(message);
  }

  class Worker extends Thread {

    @Override public void run() {
      super.run();

      List<Message> messageList = new ArrayList<>();
      List<Batch> failedBatches = new ArrayList<>();

      try {
        while (true) {
          Message message = messageQueue.take();
          messageList.add(message);

          if (messageList.size() >= size) {
            Batch batch = Batch.create(messageList, CONTEXT, 0);
            if (!upload(batch)) {
              failedBatches.add(batch);
            } else {
              Iterator<Batch> failedBatchesIterator = failedBatches.iterator();
              while (failedBatchesIterator.hasNext()) {
                Batch failed = failedBatchesIterator.next();
                Batch retry =
                    Batch.create(failed.batch(), failed.context(), failed.retryCount() + 1);
                if (upload(retry)) {
                  failedBatchesIterator.remove();
                }
              }
            }

            messageList = new ArrayList<>();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    boolean upload(Batch batch) {
      try {
        UploadResponse response = service.upload(batch);
        if (response.success()) {
          log.v("Uploaded batch.");
        } else {
          log.v("Could not upload batch.");
        }
        return response.success();
      } catch (RetrofitError error) {
        log.e(error, "Could not upload batch.");
        return false;
      }
    }
  }
}
