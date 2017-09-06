package com.bitnei.sync;

import com.bitnei.kafka.consumer.ConsumerExecutor;
import com.bitnei.kafka.resolver.VehicleResolver;

/**
 * Created by IntelliJ IDEA.
 * User: zhaogd
 * Date: 2017/8/24
 */
public class VehicleSync {

    /**
     * 同步车辆信息入口
     *
     */
    public static void main(String[] args) {
        ConsumerExecutor.execute("SYNC_DATABASE_ES", new VehicleResolver());
    }
}