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

import com.alibaba.nacos.naming.misc.GlobalExecutor;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * 表示raft的一个节点
 *
 * @author nacos
 */
public class RaftPeer {

    public String ip;

    /**
     *
     * IP
     *
     */
    public String voteFor;

    /**
     *
     * 全局唯一自增ID，可以认为是投票轮数
     *
     */
    public AtomicLong term = new AtomicLong(0L);

    public volatile long leaderDueMs = RandomUtils.nextLong(0, GlobalExecutor.LEADER_TIMEOUT_MS);

    // 心跳剩余时间，
    public volatile long heartbeatDueMs = RandomUtils.nextLong(0, GlobalExecutor.HEARTBEAT_INTERVAL_MS);

    /**
     *
     * 节点状态， 初始为follower
     *
     */
    public State state = State.FOLLOWER;

    /**
     *  重置leader到期时间，当leaderDueMs小于0，开始发起投票
     *  这里 leaderDueMs 会加上一个随机值，避免平票
     *
     */
    public void resetLeaderDue() {
        leaderDueMs = GlobalExecutor.LEADER_TIMEOUT_MS + RandomUtils.nextLong(0, GlobalExecutor.RANDOM_MS);
    }

    /**
     *
     *  重置心跳到期时间，当heartbeatDueMs小于0，表示没有收到心跳，则开始选举。
     *
     */
    public void resetHeartbeatDue() {
        heartbeatDueMs = GlobalExecutor.HEARTBEAT_INTERVAL_MS;
    }

    public enum State {
        /**
         * Leader of the cluster, only one leader stands in a cluster
         */
        LEADER,
        /**
         * Follower of the cluster, report to and copy from leader
         */
        FOLLOWER,
        /**
         * Candidate leader to be elected
         */
        CANDIDATE
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (!(obj instanceof RaftPeer)) {
            return false;
        }

        RaftPeer other = (RaftPeer) obj;

        return StringUtils.equals(ip, other.ip);
    }
}
