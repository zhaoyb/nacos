/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.naming.consistency.persistent.raft;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.nacos.naming.boot.RunningConfig;
import com.alibaba.nacos.naming.consistency.ApplyAction;
import com.alibaba.nacos.naming.consistency.Datum;
import com.alibaba.nacos.naming.consistency.KeyBuilder;
import com.alibaba.nacos.naming.consistency.RecordListener;
import com.alibaba.nacos.naming.core.Instances;
import com.alibaba.nacos.naming.core.Service;
import com.alibaba.nacos.naming.misc.*;
import com.alibaba.nacos.naming.monitor.MetricsMonitor;
import com.alibaba.nacos.naming.pojo.Record;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.javatuples.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPOutputStream;

import static com.alibaba.nacos.core.utils.SystemUtils.STANDALONE_MODE;

/**
 * @author nacos
 */
@Component
public class RaftCore {

    public static final String API_VOTE = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/vote";

    public static final String API_BEAT = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/beat";

    public static final String API_PUB = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum";

    public static final String API_DEL = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum";

    public static final String API_GET = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum";

    public static final String API_ON_PUB = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum/commit";

    public static final String API_ON_DEL = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/datum/commit";

    public static final String API_GET_PEER = UtilsAndCommons.NACOS_NAMING_CONTEXT + "/raft/peer";

    private ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);

            t.setDaemon(true);
            t.setName("com.alibaba.nacos.naming.raft.notifier");

            return t;
        }
    });

    public static final Lock OPERATE_LOCK = new ReentrantLock();

    public static final int PUBLISH_TERM_INCREASE_COUNT = 100;

    private volatile Map<String, List<RecordListener>> listeners = new ConcurrentHashMap<>();

    private volatile ConcurrentMap<String, Datum> datums = new ConcurrentHashMap<>();

    @Autowired
    private RaftPeerSet peers;

    @Autowired
    private SwitchDomain switchDomain;

    @Autowired
    private GlobalConfig globalConfig;

    @Autowired
    private RaftProxy raftProxy;

    @Autowired
    private RaftStore raftStore;

    public volatile Notifier notifier = new Notifier();

    private boolean initialized = false;

    /**
     *
     * raft 启动入口
     *
     * @throws Exception
     */
    @PostConstruct
    public void init() throws Exception {

        Loggers.RAFT.info("initializing Raft sub-system");

        executor.submit(notifier);

        long start = System.currentTimeMillis();

        // 从磁盘上读取文件，将文件内容加入到notifier
        raftStore.loadDatums(notifier, datums);

        setTerm(NumberUtils.toLong(raftStore.loadMeta().getProperty("term"), 0L));

        Loggers.RAFT.info("cache loaded, datum count: {}, current term: {}", datums.size(), peers.getTerm());

        while (true) {
            if (notifier.tasks.size() <= 0) {
                break;
            }
            Thread.sleep(1000L);
        }

        initialized = true;

        Loggers.RAFT.info("finish to load data from disk, cost: {} ms.", (System.currentTimeMillis() - start));

        // 作为一个follower ， 如果长时间没有收到心跳就开始选举
        GlobalExecutor.registerMasterElection(new MasterElection());
        // 作为一个leader， 发送心跳
        GlobalExecutor.registerHeartbeat(new HeartBeat());

        Loggers.RAFT.info("timer started: leader timeout ms: {}, heart-beat timeout ms: {}",
            GlobalExecutor.LEADER_TIMEOUT_MS, GlobalExecutor.HEARTBEAT_INTERVAL_MS);
    }

    public Map<String, List<RecordListener>> getListeners() {
        return listeners;
    }

    /**
     *  写数据
     *
     *
     * @param key
     * @param value
     * @throws Exception
     */
    public void signalPublish(String key, Record value) throws Exception {

        // 非leader节点，不接收写请求，转交leader处理
        if (!isLeader()) {
            JSONObject params = new JSONObject();
            params.put("key", key);
            params.put("value", value);
            Map<String, String> parameters = new HashMap<>(1);
            parameters.put("key", key);

            raftProxy.proxyPostLarge(getLeader().ip, API_PUB, params.toJSONString(), parameters);
            return;
        }

        try {
            // 加锁
            OPERATE_LOCK.lock();
            long start = System.currentTimeMillis();
            // 组织数据
            final Datum datum = new Datum();
            datum.key = key;
            datum.value = value;
            if (getDatum(key) == null) {
                datum.timestamp.set(1L);
            } else {
                datum.timestamp.set(getDatum(key).timestamp.incrementAndGet());
            }

            JSONObject json = new JSONObject();
            json.put("datum", datum);
            // source 表示这个请求是从哪个节点发的
            json.put("source", peers.local());

            // leader节点先写入
            onPublish(datum, peers.local());

            final String content = JSON.toJSONString(json);

            final CountDownLatch latch = new CountDownLatch(peers.majorityCount());
            for (final String server : peers.allServersIncludeMyself()) {
                if (isLeader(server)) {
                    latch.countDown();
                    continue;
                }
                final String url = buildURL(server, API_ON_PUB);
                HttpClient.asyncHttpPostLarge(url, Arrays.asList("key=" + key), content, new AsyncCompletionHandler<Integer>() {
                    @Override
                    public Integer onCompleted(Response response) throws Exception {
                        if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                            Loggers.RAFT.warn("[RAFT] failed to publish data to peer, datumId={}, peer={}, http code={}",
                                datum.key, server, response.getStatusCode());
                            return 1;
                        }
                        latch.countDown();
                        return 0;
                    }

                    @Override
                    public STATE onContentWriteCompleted() {
                        return STATE.CONTINUE;
                    }
                });

            }

            if (!latch.await(UtilsAndCommons.RAFT_PUBLISH_TIMEOUT, TimeUnit.MILLISECONDS)) {
                // only majority servers return success can we consider this update success
                Loggers.RAFT.error("data publish failed, caused failed to notify majority, key={}", key);
                throw new IllegalStateException("data publish failed, caused failed to notify majority, key=" + key);
            }

            long end = System.currentTimeMillis();
            Loggers.RAFT.info("signalPublish cost {} ms, key: {}", (end - start), key);
        } finally {
            OPERATE_LOCK.unlock();
        }
    }

    public void signalDelete(final String key) throws Exception {

        OPERATE_LOCK.lock();
        try {

            if (!isLeader()) {
                Map<String, String> params = new HashMap<>(1);
                params.put("key", URLEncoder.encode(key, "UTF-8"));
                raftProxy.proxy(getLeader().ip, API_DEL, params, HttpMethod.DELETE);
                return;
            }

            JSONObject json = new JSONObject();
            // construct datum:
            Datum datum = new Datum();
            datum.key = key;
            json.put("datum", datum);
            json.put("source", peers.local());

            onDelete(datum.key, peers.local());

            for (final String server : peers.allServersWithoutMySelf()) {
                String url = buildURL(server, API_ON_DEL);
                HttpClient.asyncHttpDeleteLarge(url, null, JSON.toJSONString(json)
                    , new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.warn("[RAFT] failed to delete data from peer, datumId={}, peer={}, http code={}", key, server, response.getStatusCode());
                                return 1;
                            }

                            RaftPeer local = peers.local();

                            local.resetLeaderDue();

                            return 0;
                        }
                    });
            }
        } finally {
            OPERATE_LOCK.unlock();
        }
    }

    public void onPublish(Datum datum, RaftPeer source) throws Exception {
        RaftPeer local = peers.local();
        if (datum.value == null) {
            Loggers.RAFT.warn("received empty datum");
            throw new IllegalStateException("received empty datum");
        }

        // 非leader发送的请求，不接收
        if (!peers.isLeader(source.ip)) {
            Loggers.RAFT.warn("peer {} tried to publish data but wasn't leader, leader: {}",
                JSON.toJSONString(source), JSON.toJSONString(getLeader()));
            throw new IllegalStateException("peer(" + source.ip + ") tried to publish " +
                "data but wasn't leader");
        }

        //term对比
        if (source.term.get() < local.term.get()) {
            Loggers.RAFT.warn("out of date publish, pub-term: {}, cur-term: {}",
                JSON.toJSONString(source), JSON.toJSONString(local));
            throw new IllegalStateException("out of date publish, pub-term:"
                + source.term.get() + ", cur-term: " + local.term.get());
        }

        local.resetLeaderDue();

        // if data should be persisted, usually this is true:
        // 是否需要持久化
        if (KeyBuilder.matchPersistentKey(datum.key)) {
            // 写入本地
            raftStore.write(datum);
        }

        //放入内存
        datums.put(datum.key, datum);

        if (isLeader()) {
            local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
        } else {
            if (local.term.get() + PUBLISH_TERM_INCREASE_COUNT > source.term.get()) {
                //set leader term:
                getLeader().term.set(source.term.get());
                local.term.set(getLeader().term.get());
            } else {
                local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
            }
        }
        raftStore.updateTerm(local.term.get());

        notifier.addTask(datum.key, ApplyAction.CHANGE);

        Loggers.RAFT.info("data added/updated, key={}, term={}", datum.key, local.term);
    }

    public void onDelete(String datumKey, RaftPeer source) throws Exception {

        RaftPeer local = peers.local();

        if (!peers.isLeader(source.ip)) {
            Loggers.RAFT.warn("peer {} tried to publish data but wasn't leader, leader: {}",
                JSON.toJSONString(source), JSON.toJSONString(getLeader()));
            throw new IllegalStateException("peer(" + source.ip + ") tried to publish data but wasn't leader");
        }

        if (source.term.get() < local.term.get()) {
            Loggers.RAFT.warn("out of date publish, pub-term: {}, cur-term: {}",
                JSON.toJSONString(source), JSON.toJSONString(local));
            throw new IllegalStateException("out of date publish, pub-term:"
                + source.term + ", cur-term: " + local.term);
        }

        local.resetLeaderDue();

        // do apply
        String key = datumKey;
        deleteDatum(key);

        if (KeyBuilder.matchServiceMetaKey(key)) {

            if (local.term.get() + PUBLISH_TERM_INCREASE_COUNT > source.term.get()) {
                //set leader term:
                getLeader().term.set(source.term.get());
                local.term.set(getLeader().term.get());
            } else {
                local.term.addAndGet(PUBLISH_TERM_INCREASE_COUNT);
            }

            raftStore.updateTerm(local.term.get());
        }

        Loggers.RAFT.info("data removed, key={}, term={}", datumKey, local.term);

    }

    public class MasterElection implements Runnable {
        @Override
        public void run() {
            try {

                if (!peers.isReady()) {
                    return;
                }

                RaftPeer local = peers.local();
                local.leaderDueMs -= GlobalExecutor.TICK_PERIOD_MS;

                if (local.leaderDueMs > 0) {
                    return;
                }

                // reset timeout
                local.resetLeaderDue();
                local.resetHeartbeatDue();

                sendVote();
            } catch (Exception e) {
                Loggers.RAFT.warn("[RAFT] error while master election {}", e);
            }

        }

        /**
         *
         * 发送投票
         *
         */
        public void sendVote() {

            //当前节点
            RaftPeer local = peers.get(NetUtils.localServer());
            Loggers.RAFT.info("leader timeout, start voting,leader: {}, term: {}",
                JSON.toJSONString(getLeader()), local.term);

            // 数据重置
            peers.reset();

            // 投票轮数+1
            local.term.incrementAndGet();
            // 投票给自己
            local.voteFor = local.ip;
            // 设置自己当前状态为candidate，表示正在投票状态
            local.state = RaftPeer.State.CANDIDATE;

            Map<String, String> params = new HashMap<>(1);
            params.put("vote", JSON.toJSONString(local));
            // 向其他节点发送投票信息，
            for (final String server : peers.allServersWithoutMySelf()) {
                final String url = buildURL(server, API_VOTE);
                try {
                    HttpClient.asyncHttpPost(url, null, params, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.error("NACOS-RAFT vote failed: {}, url: {}", response.getResponseBody(), url);
                                return 1;
                            }

                            // 响应结果
                            RaftPeer peer = JSON.parseObject(response.getResponseBody(), RaftPeer.class);

                            Loggers.RAFT.info("received approve from peer: {}", JSON.toJSONString(peer));

                            //裁决leader
                            peers.decideLeader(peer);

                            return 0;
                        }
                    });
                } catch (Exception e) {
                    Loggers.RAFT.warn("error while sending vote to server: {}", server);
                }
            }
        }
    }

    /**
     *
     * 收到投票
     *
     * @param remote
     * @return
     */
    public synchronized RaftPeer receivedVote(RaftPeer remote) {
        // 集合中不包含 投票的peer，
        if (!peers.contains(remote)) {
            throw new IllegalStateException("can not find peer: " + remote.ip);
        }

        RaftPeer local = peers.get(NetUtils.localServer());

        // 如果发起投票的节点term 比当前节点term还小，则当前节点表示，自己更适合当大哥。自己给自己投
        if (remote.term.get() <= local.term.get()) {
            String msg = "received illegitimate vote" +
                ", voter-term:" + remote.term + ", votee-term:" + local.term;

            Loggers.RAFT.info(msg);
            if (StringUtils.isEmpty(local.voteFor)) {
                // 自己投给自己
                local.voteFor = local.ip;
            }
            // 告诉发起投票那小子，我更适合当老大
            return local;
        }

        // 另外一种情况，发起投票的节点term 比收到投票节点term大   收到投票的节点表示，你牛逼，我投票给你，自己当甘做小弟

        // 重置本地leader剩余有效期
        local.resetLeaderDue();

        // 将当前节点设置为follower
        local.state = RaftPeer.State.FOLLOWER;
        // 投票给对方
        local.voteFor = remote.ip;
        // 同步轮数
        local.term.set(remote.term.get());

        Loggers.RAFT.info("vote {} as leader, term: {}", remote.ip, remote.term);

        return local;
    }

    public class HeartBeat implements Runnable {
        @Override
        public void run() {
            try {

                if (!peers.isReady()) {
                    return;
                }

                // 如果本地心跳有效期大于0，
                RaftPeer local = peers.local();
                local.heartbeatDueMs -= GlobalExecutor.TICK_PERIOD_MS;
                if (local.heartbeatDueMs > 0) {
                    return;
                }

                local.resetHeartbeatDue();

                //发送心跳
                sendBeat();
            } catch (Exception e) {
                Loggers.RAFT.warn("[RAFT] error while sending beat {}", e);
            }

        }

        public void sendBeat() throws IOException, InterruptedException {
            // 表示当前节点
            RaftPeer local = peers.local();

            // 这个判断， 其实表示的是 只有在分布式下， leader 节点才会发送心跳
            if (local.state != RaftPeer.State.LEADER && !STANDALONE_MODE) {
                return;
            }

            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT.debug("[RAFT] send beat with {} keys.", datums.size());
            }

            // 开始发送心跳，  重置当前节点 leader到期时间
            local.resetLeaderDue();

            // build data
            JSONObject packet = new JSONObject();
            packet.put("peer", local);

            JSONArray array = new JSONArray();

            if (switchDomain.isSendBeatOnly()) {
                Loggers.RAFT.info("[SEND-BEAT-ONLY] {}", String.valueOf(switchDomain.isSendBeatOnly()));
            }

            // 发送心跳的同时，携带数据
            if (!switchDomain.isSendBeatOnly()) {
                for (Datum datum : datums.values()) {

                    JSONObject element = new JSONObject();

                    if (KeyBuilder.matchServiceMetaKey(datum.key)) {
                        element.put("key", KeyBuilder.briefServiceMetaKey(datum.key));
                    } else if (KeyBuilder.matchInstanceListKey(datum.key)) {
                        element.put("key", KeyBuilder.briefInstanceListkey(datum.key));
                    }
                    element.put("timestamp", datum.timestamp);

                    array.add(element);
                }
            }

            packet.put("datums", array);
            // broadcast
            Map<String, String> params = new HashMap<String, String>(1);
            params.put("beat", JSON.toJSONString(packet));

            // tostring
            String content = JSON.toJSONString(params);

            //压缩
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(out);
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
            gzip.close();

            byte[] compressedBytes = out.toByteArray();
            String compressedContent = new String(compressedBytes, StandardCharsets.UTF_8);

            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT.debug("raw beat data size: {}, size of compressed data: {}",
                    content.length(), compressedContent.length());
            }

            // allServersWithoutMySelf : 排除自己， 其他所有的节点
            // 向所有节点发送心跳
            for (final String server : peers.allServersWithoutMySelf()) {
                try {
                    // 心跳url
                    final String url = buildURL(server, API_BEAT);
                    if (Loggers.RAFT.isDebugEnabled()) {
                        Loggers.RAFT.debug("send beat to server " + server);
                    }
                    // 发送心跳请求
                    HttpClient.asyncHttpPostLarge(url, null, compressedBytes, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.error("NACOS-RAFT beat failed: {}, peer: {}",
                                    response.getResponseBody(), server);
                                // 错误累加
                                MetricsMonitor.getLeaderSendBeatFailedException().increment();
                                return 1;
                            }

                            // 更新当前节点上的指定peers， 心跳返回值是 接收到心跳的节点信息
                            peers.update(JSON.parseObject(response.getResponseBody(), RaftPeer.class));
                            if (Loggers.RAFT.isDebugEnabled()) {
                                Loggers.RAFT.debug("receive beat response from: {}", url);
                            }
                            return 0;
                        }

                        @Override
                        public void onThrowable(Throwable t) {
                            Loggers.RAFT.error("NACOS-RAFT error while sending heart-beat to peer: {} {}", server, t);
                            // 错误累加
                            MetricsMonitor.getLeaderSendBeatFailedException().increment();
                        }
                    });
                } catch (Exception e) {
                    Loggers.RAFT.error("error while sending heart-beat to peer: {} {}", server, e);
                    // 错误累加
                    MetricsMonitor.getLeaderSendBeatFailedException().increment();
                }
            }

        }
    }

    /**
     *
     *  当作为follow节点，接收心跳
     *
     * @param beat
     * @return
     * @throws Exception
     */
    public RaftPeer receivedBeat(JSONObject beat) throws Exception {
        // 当前raft节点
        final RaftPeer local = peers.local();

        // 发送心跳的那个raft节点
        final RaftPeer remote = new RaftPeer();
        remote.ip = beat.getJSONObject("peer").getString("ip");
        remote.state = RaftPeer.State.valueOf(beat.getJSONObject("peer").getString("state"));
        remote.term.set(beat.getJSONObject("peer").getLongValue("term"));
        remote.heartbeatDueMs = beat.getJSONObject("peer").getLongValue("heartbeatDueMs");
        remote.leaderDueMs = beat.getJSONObject("peer").getLongValue("leaderDueMs");
        remote.voteFor = beat.getJSONObject("peer").getString("voteFor");

        // 发送心跳的节点应该是leader节点， 如果非leader， 表示是一个无效的心跳，报错
        if (remote.state != RaftPeer.State.LEADER) {
            Loggers.RAFT.info("[RAFT] invalid state from master, state: {}, remote peer: {}",
                remote.state, JSON.toJSONString(remote));
            throw new IllegalArgumentException("invalid state from master, state: " + remote.state);
        }

        // 如果自己的term大于leader的，表示是一个过期的心跳  报错
        if (local.term.get() > remote.term.get()) {
            Loggers.RAFT.info("[RAFT] out of date beat, beat-from-term: {}, beat-to-term: {}, remote peer: {}, and leaderDueMs: {}"
                , remote.term.get(), local.term.get(), JSON.toJSONString(remote), local.leaderDueMs);
            throw new IllegalArgumentException("out of date beat, beat-from-term: " + remote.term.get()
                + ", beat-to-term: " + local.term.get());
        }

        if (local.state != RaftPeer.State.FOLLOWER) {

            Loggers.RAFT.info("[RAFT] make remote as leader, remote peer: {}", JSON.toJSONString(remote));
            // mk follower ,将当前节点设置为follower节点
            local.state = RaftPeer.State.FOLLOWER;
            
            //更新本地的投票，投票给leader
            local.voteFor = remote.ip;
        }

        // 心跳携带的数据
        final JSONArray beatDatums = beat.getJSONArray("datums");

        // 作为follower节点，收到心跳后，表示leader还活着，就重置LeaderDue， 表示再隔一定时间就去选举
        local.resetLeaderDue();

        // 重置心跳
        local.resetHeartbeatDue();

        // 设置新的leader
        peers.makeLeader(remote);

        //对心跳携带数据进行处理
        if (!switchDomain.isSendBeatOnly()) {

            Map<String, Integer> receivedKeysMap = new HashMap<>(datums.size());

            for (Map.Entry<String, Datum> entry : datums.entrySet()) {
                receivedKeysMap.put(entry.getKey(), 0);
            }

            // now check datums
            List<String> batch = new ArrayList<>();

            int processedCount = 0;
            if (Loggers.RAFT.isDebugEnabled()) {
                Loggers.RAFT.debug("[RAFT] received beat with {} keys, RaftCore.datums' size is {}, remote server: {}, term: {}, local term: {}",
                    beatDatums.size(), datums.size(), remote.ip, remote.term, local.term);
            }
            for (Object object : beatDatums) {
                processedCount = processedCount + 1;

                JSONObject entry = (JSONObject) object;
                String key = entry.getString("key");
                final String datumKey;

                if (KeyBuilder.matchServiceMetaKey(key)) {
                    datumKey = KeyBuilder.detailServiceMetaKey(key);
                } else if (KeyBuilder.matchInstanceListKey(key)) {
                    datumKey = KeyBuilder.detailInstanceListkey(key);
                } else {
                    // ignore corrupted key:
                    continue;
                }

                long timestamp = entry.getLong("timestamp");

                receivedKeysMap.put(datumKey, 1);

                try {
                    if (datums.containsKey(datumKey) && datums.get(datumKey).timestamp.get() >= timestamp && processedCount < beatDatums.size()) {
                        continue;
                    }

                    if (!(datums.containsKey(datumKey) && datums.get(datumKey).timestamp.get() >= timestamp)) {
                        batch.add(datumKey);
                    }

                    if (batch.size() < 50 && processedCount < beatDatums.size()) {
                        continue;
                    }

                    String keys = StringUtils.join(batch, ",");

                    if (batch.size() <= 0) {
                        continue;
                    }

                    Loggers.RAFT.info("get datums from leader: {}, batch size is {}, processedCount is {}, datums' size is {}, RaftCore.datums' size is {}"
                        , getLeader().ip, batch.size(), processedCount, beatDatums.size(), datums.size());

                    // update datum entry
                    String url = buildURL(remote.ip, API_GET) + "?keys=" + URLEncoder.encode(keys, "UTF-8");
                    HttpClient.asyncHttpGet(url, null, null, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                return 1;
                            }

                            List<JSONObject> datumList = JSON.parseObject(response.getResponseBody(), new TypeReference<List<JSONObject>>() {
                            });

                            for (JSONObject datumJson : datumList) {
                                OPERATE_LOCK.lock();
                                Datum newDatum = null;
                                try {

                                    Datum oldDatum = getDatum(datumJson.getString("key"));

                                    if (oldDatum != null && datumJson.getLongValue("timestamp") <= oldDatum.timestamp.get()) {
                                        Loggers.RAFT.info("[NACOS-RAFT] timestamp is smaller than that of mine, key: {}, remote: {}, local: {}",
                                            datumJson.getString("key"), datumJson.getLongValue("timestamp"), oldDatum.timestamp);
                                        continue;
                                    }

                                    if (KeyBuilder.matchServiceMetaKey(datumJson.getString("key"))) {
                                        Datum<Service> serviceDatum = new Datum<>();
                                        serviceDatum.key = datumJson.getString("key");
                                        serviceDatum.timestamp.set(datumJson.getLongValue("timestamp"));
                                        serviceDatum.value =
                                            JSON.parseObject(JSON.toJSONString(datumJson.getJSONObject("value")), Service.class);
                                        newDatum = serviceDatum;
                                    }

                                    if (KeyBuilder.matchInstanceListKey(datumJson.getString("key"))) {
                                        Datum<Instances> instancesDatum = new Datum<>();
                                        instancesDatum.key = datumJson.getString("key");
                                        instancesDatum.timestamp.set(datumJson.getLongValue("timestamp"));
                                        instancesDatum.value =
                                            JSON.parseObject(JSON.toJSONString(datumJson.getJSONObject("value")), Instances.class);
                                        newDatum = instancesDatum;
                                    }

                                    if (newDatum == null || newDatum.value == null) {
                                        Loggers.RAFT.error("receive null datum: {}", datumJson);
                                        continue;
                                    }

                                    raftStore.write(newDatum);

                                    datums.put(newDatum.key, newDatum);
                                    notifier.addTask(newDatum.key, ApplyAction.CHANGE);

                                    local.resetLeaderDue();

                                    if (local.term.get() + 100 > remote.term.get()) {
                                        getLeader().term.set(remote.term.get());
                                        local.term.set(getLeader().term.get());
                                    } else {
                                        local.term.addAndGet(100);
                                    }

                                    raftStore.updateTerm(local.term.get());

                                    Loggers.RAFT.info("data updated, key: {}, timestamp: {}, from {}, local term: {}",
                                        newDatum.key, newDatum.timestamp, JSON.toJSONString(remote), local.term);

                                } catch (Throwable e) {
                                    Loggers.RAFT.error("[RAFT-BEAT] failed to sync datum from leader, datum: {}", newDatum, e);
                                } finally {
                                    OPERATE_LOCK.unlock();
                                }
                            }
                            TimeUnit.MILLISECONDS.sleep(200);
                            return 0;
                        }
                    });

                    batch.clear();

                } catch (Exception e) {
                    Loggers.RAFT.error("[NACOS-RAFT] failed to handle beat entry, key: {}", datumKey);
                }

            }

            List<String> deadKeys = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : receivedKeysMap.entrySet()) {
                if (entry.getValue() == 0) {
                    deadKeys.add(entry.getKey());
                }
            }

            for (String deadKey : deadKeys) {
                try {
                    deleteDatum(deadKey);
                } catch (Exception e) {
                    Loggers.RAFT.error("[NACOS-RAFT] failed to remove entry, key={} {}", deadKey, e);
                }
            }

        }

        return local;
    }

    public void listen(String key, RecordListener listener) {

        List<RecordListener> listenerList = listeners.get(key);
        if (listenerList != null && listenerList.contains(listener)) {
            return;
        }

        if (listenerList == null) {
            listenerList = new CopyOnWriteArrayList<>();
            listeners.put(key, listenerList);
        }

        Loggers.RAFT.info("add listener: {}", key);

        listenerList.add(listener);

        // if data present, notify immediately
        for (Datum datum : datums.values()) {
            if (!listener.interests(datum.key)) {
                continue;
            }

            try {
                listener.onChange(datum.key, datum.value);
            } catch (Exception e) {
                Loggers.RAFT.error("NACOS-RAFT failed to notify listener", e);
            }
        }
    }

    public void unlisten(String key, RecordListener listener) {

        if (!listeners.containsKey(key)) {
            return;
        }

        for (RecordListener dl : listeners.get(key)) {
            // TODO maybe use equal:
            if (dl == listener) {
                listeners.get(key).remove(listener);
                break;
            }
        }
    }

    public void unlistenAll(String key) {
        listeners.remove(key);
    }

    public void setTerm(long term) {
        peers.setTerm(term);
    }

    public boolean isLeader(String ip) {
        return peers.isLeader(ip);
    }

    public boolean isLeader() {
        return peers.isLeader(NetUtils.localServer());
    }

    public static String buildURL(String ip, String api) {
        if (!ip.contains(UtilsAndCommons.IP_PORT_SPLITER)) {
            ip = ip + UtilsAndCommons.IP_PORT_SPLITER + RunningConfig.getServerPort();
        }
        return "http://" + ip + RunningConfig.getContextPath() + api;
    }

    public Datum<?> getDatum(String key) {
        return datums.get(key);
    }

    public RaftPeer getLeader() {
        return peers.getLeader();
    }

    public List<RaftPeer> getPeers() {
        return new ArrayList<>(peers.allPeers());
    }

    public RaftPeerSet getPeerSet() {
        return peers;
    }

    public void setPeerSet(RaftPeerSet peerSet) {
        peers = peerSet;
    }

    public int datumSize() {
        return datums.size();
    }

    public void addDatum(Datum datum) {
        datums.put(datum.key, datum);
        notifier.addTask(datum.key, ApplyAction.CHANGE);
    }

    public void loadDatum(String key) {
        try {
            Datum datum = raftStore.load(key);
            if (datum == null) {
                return;
            }
            datums.put(key, datum);
        } catch (Exception e) {
            Loggers.RAFT.error("load datum failed: " + key, e);
        }

    }

    private void deleteDatum(String key) {
        Datum deleted;
        try {
            deleted = datums.remove(URLDecoder.decode(key, "UTF-8"));
            if (deleted != null) {
                raftStore.delete(deleted);
                Loggers.RAFT.info("datum deleted, key: {}", key);
            }
            notifier.addTask(URLDecoder.decode(key, "UTF-8"), ApplyAction.DELETE);
        } catch (UnsupportedEncodingException e) {
            Loggers.RAFT.warn("datum key decode failed: {}", key);
        }
    }

    public boolean isInitialized() {
        return initialized || !globalConfig.isDataWarmup();
    }

    public int getNotifyTaskCount() {
        return notifier.getTaskSize();
    }

    public class Notifier implements Runnable {

        private ConcurrentHashMap<String, String> services = new ConcurrentHashMap<>(10 * 1024);

        private BlockingQueue<Pair> tasks = new LinkedBlockingQueue<>(1024 * 1024);

        public void addTask(String datumKey, ApplyAction action) {

            if (services.containsKey(datumKey) && action == ApplyAction.CHANGE) {
                return;
            }
            if (action == ApplyAction.CHANGE) {
                services.put(datumKey, StringUtils.EMPTY);
            }

            Loggers.RAFT.info("add task {}", datumKey);

            tasks.add(Pair.with(datumKey, action));
        }

        public int getTaskSize() {
            return tasks.size();
        }

        @Override
        public void run() {
            Loggers.RAFT.info("raft notifier started");

            while (true) {
                try {

                    Pair pair = tasks.take();

                    if (pair == null) {
                        continue;
                    }

                    String datumKey = (String) pair.getValue0();
                    ApplyAction action = (ApplyAction) pair.getValue1();

                    services.remove(datumKey);

                    Loggers.RAFT.info("remove task {}", datumKey);

                    int count = 0;

                    if (listeners.containsKey(KeyBuilder.SERVICE_META_KEY_PREFIX)) {

                        if (KeyBuilder.matchServiceMetaKey(datumKey) && !KeyBuilder.matchSwitchKey(datumKey)) {

                            for (RecordListener listener : listeners.get(KeyBuilder.SERVICE_META_KEY_PREFIX)) {
                                try {
                                    if (action == ApplyAction.CHANGE) {
                                        listener.onChange(datumKey, getDatum(datumKey).value);
                                    }

                                    if (action == ApplyAction.DELETE) {
                                        listener.onDelete(datumKey);
                                    }
                                } catch (Throwable e) {
                                    Loggers.RAFT.error("[NACOS-RAFT] error while notifying listener of key: {}", datumKey, e);
                                }
                            }
                        }
                    }

                    if (!listeners.containsKey(datumKey)) {
                        continue;
                    }

                    for (RecordListener listener : listeners.get(datumKey)) {

                        count++;

                        try {
                            if (action == ApplyAction.CHANGE) {
                                listener.onChange(datumKey, getDatum(datumKey).value);
                                continue;
                            }

                            if (action == ApplyAction.DELETE) {
                                listener.onDelete(datumKey);
                                continue;
                            }
                        } catch (Throwable e) {
                            Loggers.RAFT.error("[NACOS-RAFT] error while notifying listener of key: {}", datumKey, e);
                        }
                    }

                    if (Loggers.RAFT.isDebugEnabled()) {
                        Loggers.RAFT.debug("[NACOS-RAFT] datum change notified, key: {}, listener count: {}", datumKey, count);
                    }
                } catch (Throwable e) {
                    Loggers.RAFT.error("[NACOS-RAFT] Error while handling notifying task", e);
                }
            }
        }
    }
}
