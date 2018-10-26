/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.AtomicPositiveInteger;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Round robin load balance.
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "roundrobin";

    private final ConcurrentMap<String, AtomicPositiveInteger> sequences = new ConcurrentHashMap<String, AtomicPositiveInteger>();

    private final ConcurrentMap<String, AtomicPositiveInteger> indexSeqs = new ConcurrentHashMap<String, AtomicPositiveInteger>();

    /*
     *  加权轮询算法
     *
     *  1 依次遍历所有可用的节点，选择比权限基准线大的节点
     *  2 每完成一轮迭代，在最大权重内不断提升权重基准线
     *  3 随着迭代次数增加，权重基准线不断抬高，只有权重高的节点会被选择
     *  4 到达最大权重后，重新执行上述过程
     *
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        int length = invokers.size(); // Number of invokers
        int maxWeight = 0; // The maximum weight
        int minWeight = Integer.MAX_VALUE; // The minimum weight

        //加权轮询时，仅轮询权重>0的节点
        final List<Invoker<T>> nonZeroWeightedInvokers = new ArrayList<>();

        for (int i = 0; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            maxWeight = Math.max(maxWeight, weight); // Choose the maximum weight
            minWeight = Math.min(minWeight, weight); // Choose the minimum weight
            if (weight > 0) {
                nonZeroWeightedInvokers.add(invokers.get(i));
            }
        }

        //CAS自增序列号， 为每一个方法维护一个序列号，每次方法调用，方法的序列号就自增一次，轮询下一个节点
        AtomicPositiveInteger sequence = sequences.get(key);
        if (sequence == null) {
            sequences.putIfAbsent(key, new AtomicPositiveInteger());
            sequence = sequences.get(key);
        }

        //加权轮询
        if (maxWeight > 0 && minWeight < maxWeight) {

            AtomicPositiveInteger indexSeq = indexSeqs.get(key);
            if (indexSeq == null) {
                indexSeqs.putIfAbsent(key, new AtomicPositiveInteger(-1));
                indexSeq = indexSeqs.get(key);
            }
            length = nonZeroWeightedInvokers.size();

            //每迭代一轮更新一次当前权重(权重基准)，一轮内比当前权重大的节点可被选择
            //在一个maxWeight周期内，随着迭代轮增加，权重基准也抬高，只有高权重的节点会被选择
            while (true) {
                //迭代每个节点
                int index = indexSeq.incrementAndGet() % length;
                int currentWeight;
                if (index == 0) { //每迭代一轮更新一次当前权重，比当前权重大的节点可被选择
                    currentWeight = sequence.incrementAndGet() % maxWeight;
                } else {
                    currentWeight = sequence.get() % maxWeight;
                }


                if (getWeight(nonZeroWeightedInvokers.get(index), invocation) > currentWeight) {
                    return nonZeroWeightedInvokers.get(index);
                }
            }
        }

        // Round robin平均轮询
        return invokers.get(sequence.getAndIncrement() % length);
    }
}
