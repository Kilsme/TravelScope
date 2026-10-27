package com.travelscope.service.route;

import java.time.LocalTime;

/** 日落时间提供器；测试可注入固定实现，生产默认实现不访问网络。 */
@FunctionalInterface
public interface SunTimeProvider {
    LocalTime sunset(String city);
}
