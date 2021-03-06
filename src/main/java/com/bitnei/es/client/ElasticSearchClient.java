package com.bitnei.es.client;

import com.bitnei.es.client.ElasticSearchConfig.BulkProcessorConfig;
import com.bitnei.es.client.ElasticSearchConfig.ClientConfig;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.reindex.AbstractBulkByScrollRequest;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.UpdateByQueryAction;
import org.elasticsearch.index.reindex.UpdateByQueryRequestBuilder;
import org.elasticsearch.script.Script;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ES客户端
 */
public class ElasticSearchClient {

    private static final Logger logger = LogManager.getLogger(ElasticSearchClient.class);

    private static final int DEFAULT_NODE_PORT = 9300;

    private static Lock commitLock = new ReentrantLock();

    private static TransportClient client;
    private static BulkProcessor bulkProcessor;

    static {
        initClient();
        initBulkProcessor();
    }

    private static void initClient() {
        logger.info("es初始化配置信息：" + ElasticSearchConfig.getInfo());

        Settings settings = Settings.builder()
                .put("cluster.name", ClientConfig.clusterName)
                .put("client.transport.sniff", ClientConfig.isSniff)
                .put("client.transport.ping_timeout", ClientConfig.pingTimeout)
                .build();

        client = new PreBuiltTransportClient(settings);
        String[] nodes = ClientConfig.nodeHosts.split(",");
        for (String node : nodes) {
            if (node.length() > 0) {// 跳过为空的node（当开头、结尾有逗号或多个连续逗号时会出现空node）
                String[] hostPort = node.split(":");
                try {
                    int port = StringUtils.isBlank(hostPort[1]) ? DEFAULT_NODE_PORT : Integer.valueOf(hostPort[1]);
                    client.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(hostPort[0]), port));
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void initBulkProcessor() {
        bulkProcessor = BulkProcessor.builder(client, new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                // 提交前调用
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                // 提交结束后调用（无论成功或失败）
                logger.info("提交" + response.getItems().length + "个文档，用时" + response.getTookInMillis() + "MS" + (response.hasFailures() ? " 有文档提交失败！" : ""));
                if (response.hasFailures()) {
                    logger.error(response.buildFailureMessage());
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                // 提交结束且失败时调用
                logger.error("有文档提交失败！after failure=", failure);
            }
        })
                .setBulkActions(BulkProcessorConfig.bulkActions)
                .setBulkSize(new ByteSizeValue(BulkProcessorConfig.bulkSize, ByteSizeUnit.MB))
                .setFlushInterval(TimeValue.timeValueSeconds(BulkProcessorConfig.flushInterval))
                .setConcurrentRequests(BulkProcessorConfig.concurrentRequests)
                .build();
    }


    /**
     * 获取es客户端
     *
     * @return 客户端
     */
    public static TransportClient getClient() {
        return client;
    }


    /**
     * 通过查询更新
     *
     * @param indexName    索引名称
     * @param queryBuilder 查询条件
     * @param script       更新脚本
     * @return 更新结果信息
     */
    public static BulkByScrollResponse updateByQuery(String indexName, QueryBuilder queryBuilder, Script script) {
        UpdateByQueryRequestBuilder updateByQueryRequestBuilder =
                UpdateByQueryAction.INSTANCE.newRequestBuilder(client)
                        .filter(queryBuilder)
                        .size(AbstractBulkByScrollRequest.SIZE_ALL_MATCHES)
                        .source(indexName)
                        .script(script);
        if (logger.isDebugEnabled()) {
            logger.debug("send request json :" + updateByQueryRequestBuilder.source().toString()
                    + "\n script :" + updateByQueryRequestBuilder.request().toString()
            );
        }
        return updateByQueryRequestBuilder.get();
    }

    /**
     * 加入索引请求到缓冲池
     *
     * @param indexName 索引名称
     * @param id        ID
     * @param json      实体
     */
    public static void addIndexRequestToBulk(String indexName, String id, Map<String, Object> json) {
        commitLock.lock();
        try {
            IndexRequest indexRequest = new IndexRequest(indexName, ClientConfig.typeName, id).source(json);
            bulkProcessor.add(indexRequest);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * 加入索引请求到缓冲池
     *
     * @param indexName  索引名称
     * @param id         ID
     * @param jsonString 索引实体
     */
    public static void addIndexRequestToBulk(String indexName, String id, String jsonString) {
        commitLock.lock();
        try {
            IndexRequest indexRequest = new IndexRequest(indexName, ClientConfig.typeName, id).source(jsonString, XContentType.JSON);
            bulkProcessor.add(indexRequest);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * 加入删除请求到缓冲池
     *
     * @param indexName 索引名称
     * @param id        ID
     */
    public static void addDeleteRequestToBulk(String indexName, String id) {
        commitLock.lock();
        try {
            DeleteRequest deleteRequest = new DeleteRequest(indexName, ClientConfig.typeName, id);
            bulkProcessor.add(deleteRequest);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * 加入更新请求到缓冲池
     *
     * @param indexName 索引名
     * @param id        id
     * @param json      更新请求体
     */
    public static void addUpdateRequestToBulk(String indexName, String id, Map<String, Object> json) {
        commitLock.lock();
        try {
            UpdateRequest updateRequest = new UpdateRequest(indexName, ClientConfig.typeName, id).doc(json).docAsUpsert(true);
            bulkProcessor.add(updateRequest);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * 加入更新请求到缓冲池
     *
     * @param indexName  索引名
     * @param id         id
     * @param jsonString 更新请求体
     */
    public static void addUpdateRequestToBulk(String indexName, String id, String jsonString) {
        commitLock.lock();
        try {
            UpdateRequest updateRequest = new UpdateRequest(indexName, ClientConfig.typeName, id).doc(jsonString, XContentType.JSON).docAsUpsert(true);
            bulkProcessor.add(updateRequest);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * 关闭连接
     */
    public static void close() {
        try {
            bulkProcessor.awaitClose(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
            logger.error(e.getMessage(), e);
        } finally {
            client.close();
        }
    }

    /**
     * 手动刷新批处理器
     */
    public static void flush() {
        bulkProcessor.flush();
    }
}
