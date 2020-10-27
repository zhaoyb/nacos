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

import static com.alibaba.nacos.core.utils.SystemUtils.STANDALONE_MODE;

import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.core.utils.SystemUtils;
import com.alibaba.nacos.naming.boot.RunningConfig;
import com.alibaba.nacos.naming.cluster.ServerListManager;
import com.alibaba.nacos.naming.cluster.servers.Server;
import com.alibaba.nacos.naming.cluster.servers.ServerChangeListener;
import com.alibaba.nacos.naming.misc.HttpClient;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.NetUtils;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.Response;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.PostConstruct;
import org.apache.commons.collections.SortedBag;
import org.apache.commons.collections.bag.TreeBag;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 表示当前节点持有的同伴信息节点
 *
 * @author nacos
 */
@Component
@DependsOn("serverListManager")
public class RaftPeerSet implements ServerChangeListener, ApplicationContextAware {

    @Autowired
    private ServerListManager serverListManager;

    private ApplicationContext applicationContext;

    private AtomicLong localTerm = new AtomicLong(0L);

    private RaftPeer leader = null;

    // IP ：raft节点
    private Map<String, RaftPeer> peers = new HashMap<>();

    private Set<String> sites = new HashSet<>();

    private boolean ready = false;

    public RaftPeerSet() {

    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {

        this.applicationContext = applicationContext;
    }

    /**
     *
     * 监听raft节点变化
     *
     */
    @PostConstruct
    public void init() {
        serverListManager.listen(this);
    }

    public RaftPeer getLeader() {
        if (STANDALONE_MODE) {
            return local();
        }
        return leader;
    }

    public Set<String> allSites() {
        return sites;
    }

    public boolean isReady() {
        return ready;
    }

    public void remove(List<String> servers) {
        for (String server : servers) {
            peers.remove(server);
        }
    }

    /**
     * 更新节点信息
     */
    public RaftPeer update(RaftPeer peer) {
        peers.put(peer.ip, peer);
        return peer;
    }

    public boolean isLeader(String ip) {
        if (STANDALONE_MODE) {
            return true;
        }

        if (leader == null) {
            Loggers.RAFT.warn("[IS LEADER] no leader is available now!");
            return false;
        }

        return StringUtils.equals(leader.ip, ip);
    }

    public Set<String> allServersIncludeMyself() {
        return peers.keySet();
    }

    public Set<String> allServersWithoutMySelf() {
        Set<String> servers = new HashSet<String>(peers.keySet());

        // exclude myself
        servers.remove(local().ip);

        return servers;
    }

    public Collection<RaftPeer> allPeers() {
        return peers.values();
    }

    public int size() {
        return peers.size();
    }

    /**
     *
     * 裁决，
     *
     * @param candidate
     * @return
     */
    public RaftPeer decideLeader(RaftPeer candidate) {
        peers.put(candidate.ip, candidate);

        // treebag 你可以认为一个带统计功能的set,假设你有一个包，包含{a, a, b, c}。调用getCount(a)方法将返回2，调用uniqueset()方法将返回{a, b, c}。
        SortedBag ips = new TreeBag();
        // 最大赞同数量
        int maxApproveCount = 0;
        // 最大赞同的ip
        String maxApprovePeer = null;
        for (RaftPeer peer : peers.values()) {
            if (StringUtils.isEmpty(peer.voteFor)) {
                continue;
            }

            ips.add(peer.voteFor);
            if (ips.getCount(peer.voteFor) > maxApproveCount) {
                maxApproveCount = ips.getCount(peer.voteFor);
                maxApprovePeer = peer.voteFor;
            }
        }
        // 最大赞同数，超过半数，表示有效
        if (maxApproveCount >= majorityCount()) {
            RaftPeer peer = peers.get(maxApprovePeer);
            // 将peer设置为leader
            peer.state = RaftPeer.State.LEADER;

            // 当前和最新的不一致
            if (!Objects.equals(leader, peer)) {
                leader = peer;
                // 选举完成
                applicationContext.publishEvent(new LeaderElectFinishedEvent(this, leader));
                Loggers.RAFT.info("{} has become the LEADER", leader.ip);
            }
        }

        return leader;
    }

    /**
     * 将指定peer 设置为leader
     */
    public RaftPeer makeLeader(RaftPeer candidate) {
        // 如果之前的leader不是现在新的leader
        if (!Objects.equals(leader, candidate)) {
            // 将新的leader设置leader
            leader = candidate;
            // 发布事件
            applicationContext.publishEvent(new MakeLeaderEvent(this, leader));
            Loggers.RAFT.info("{} has become the LEADER, local: {}, leader: {}",
                              leader.ip, JSON.toJSONString(local()), JSON.toJSONString(leader));
        }

        for (final RaftPeer peer : peers.values()) {
            Map<String, String> params = new HashMap<>(1);
            if (!Objects.equals(peer, candidate) && peer.state == RaftPeer.State.LEADER) {
                try {
                    String url = RaftCore.buildURL(peer.ip, RaftCore.API_GET_PEER);
                    HttpClient.asyncHttpGet(url, null, params, new AsyncCompletionHandler<Integer>() {
                        @Override
                        public Integer onCompleted(Response response) throws Exception {
                            if (response.getStatusCode() != HttpURLConnection.HTTP_OK) {
                                Loggers.RAFT.error("[NACOS-RAFT] get peer failed: {}, peer: {}",
                                                   response.getResponseBody(), peer.ip);
                                peer.state = RaftPeer.State.FOLLOWER;
                                return 1;
                            }

                            update(JSON.parseObject(response.getResponseBody(), RaftPeer.class));

                            return 0;
                        }
                    });
                } catch (Exception e) {
                    peer.state = RaftPeer.State.FOLLOWER;
                    Loggers.RAFT.error("[NACOS-RAFT] error while getting peer from peer: {}", peer.ip);
                }
            }
        }

        return update(candidate);
    }

    public RaftPeer local() {
        RaftPeer peer = peers.get(NetUtils.localServer());
        if (peer == null && SystemUtils.STANDALONE_MODE) {
            RaftPeer localPeer = new RaftPeer();
            localPeer.ip = NetUtils.localServer();
            localPeer.term.set(localTerm.get());
            peers.put(localPeer.ip, localPeer);
            return localPeer;
        }
        if (peer == null) {
            throw new IllegalStateException("unable to find local peer: " + NetUtils.localServer() + ", all peers: "
                                                    + Arrays.toString(peers.keySet().toArray()));
        }

        return peer;
    }

    public RaftPeer get(String server) {
        return peers.get(server);
    }

    /**
     *
     * 大多数
     *
     * @return
     */
    public int majorityCount() {
        return peers.size() / 2 + 1;
    }

    public void reset() {

        leader = null;

        for (RaftPeer peer : peers.values()) {
            peer.voteFor = null;
        }
    }

    public void setTerm(long term) {
        localTerm.set(term);
    }

    public long getTerm() {
        return localTerm.get();
    }

    public boolean contains(RaftPeer remote) {
        return peers.containsKey(remote.ip);
    }

    @Override
    public void onChangeServerList(List<Server> latestMembers) {

        Map<String, RaftPeer> tmpPeers = new HashMap<>(8);
        for (Server member : latestMembers) {

            if (peers.containsKey(member.getKey())) {
                tmpPeers.put(member.getKey(), peers.get(member.getKey()));
                continue;
            }

            RaftPeer raftPeer = new RaftPeer();
            raftPeer.ip = member.getKey();

            // first time meet the local server:
            if (NetUtils.localServer().equals(member.getKey())) {
                raftPeer.term.set(localTerm.get());
            }

            tmpPeers.put(member.getKey(), raftPeer);
        }

        // replace raft peer set:
        peers = tmpPeers;

        if (RunningConfig.getServerPort() > 0) {
            ready = true;
        }

        Loggers.RAFT.info("raft peers changed: " + latestMembers);
    }

    @Override
    public void onChangeHealthyServerList(List<Server> latestReachableMembers) {

    }
}
