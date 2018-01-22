package org.windwant.spring.core.consul;

import com.google.common.net.HostAndPort;
import com.orbitz.consul.AgentClient;
import com.orbitz.consul.Consul;
import com.orbitz.consul.HealthClient;
import com.orbitz.consul.KeyValueClient;
import com.orbitz.consul.cache.ConsulCache;
import com.orbitz.consul.cache.ServiceHealthCache;
import com.orbitz.consul.cache.ServiceHealthKey;
import com.orbitz.consul.model.agent.ImmutableRegCheck;
import com.orbitz.consul.model.agent.ImmutableRegistration;
import com.orbitz.consul.model.health.Service;
import com.orbitz.consul.model.health.ServiceHealth;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.windwant.spring.util.JedisUtils;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * consul.exe agent -server -bootstrap -data-dir=data -bind=127.0.0.1 -client 0.0.0.0 -ui
 * Created by windwant on 2016/8/18.
 */
@Component
public class ConsulMgr {

    private static final Logger logger = LoggerFactory.getLogger(ConsulMgr.class);

    @org.springframework.beans.factory.annotation.Value("${consul.host}")
    private String consulHost;
    @org.springframework.beans.factory.annotation.Value("${server.port}")
    private Integer port;

    @org.springframework.beans.factory.annotation.Value("${redis.host}")
    private String redisHost;

    @org.springframework.beans.factory.annotation.Value("${redis.port}")
    private Integer redisPort;

    private KeyValueClient keyValueClient;
    private HealthClient healthClient;
    private AgentClient agentClient;
    private String redisService = "redis";
    private String bootService = "boot";

    public void init(){
        Consul consul = Consul.builder()
                .withConnectTimeoutMillis(3000)
                .withPing(true)
                .withReadTimeoutMillis(2000)
                .withWriteTimeoutMillis(2000)
                .withHostAndPort(HostAndPort.fromParts(consulHost, 8500)).build();
        keyValueClient = consul.keyValueClient();
        healthClient = consul.healthClient();
        agentClient = consul.agentClient();

        //注册本服务到consul
        registerService(bootService, bootService, bootService, "127.0.0.1", port, 5);

        //注册测试redis服务
        registerService(redisService, redisService, redisService, redisHost, redisPort, 5);

        //获取可用redis服务
        getHealthService(redisService);

        //监控redis服务
//        watchSvr();
    }

    /**
     * 注册服务
     */
    public void registerService(String svrId, String svrName, String tags, String host, Integer port, Integer interval){
        //健康检查
        ImmutableRegCheck immutableRegCheck = ImmutableRegCheck.builder().tcp(host + ":" + port).interval(interval + "s").build();

        ImmutableRegistration immutableRegistration = ImmutableRegistration.builder().
                id(svrId).
                name(svrName).
                addTags(tags).
                address(host).
                port(port).
                addChecks(immutableRegCheck).
                build();

        agentClient.register(immutableRegistration);
    }

    /**
     * 获取正常服务
     * @param serviceName
     */
    public void getHealthService(String serviceName){
        List<ServiceHealth> nodes = healthClient.getHealthyServiceInstances(serviceName).getResponse();
        if(StringUtils.isNotBlank(JedisUtils.getHost()) && nodes.size() > 0) {
            nodes.forEach((resp) -> {
                if (StringUtils.equals(resp.getService().getAddress(), JedisUtils.getHost()) &&
                    resp.getService().getPort() == JedisUtils.getPort()) {
                    if(JedisUtils.getJedisPool().isClosed()){
                        JedisUtils.init(resp.getService().getAddress(), resp.getService().getPort());
                        return;
                    }
                    return;
                }
            });
        }

        nodes.forEach((resp) -> {
            Service service = resp.getService();
            System.out.println("service port: " + service.getPort());
            System.out.println("service address: " + service.getAddress());

            //选取一个服务器初始化redis jedispool
            ServiceHealth random = nodes.get(ThreadLocalRandom.current().nextInt(nodes.size()));
            if (JedisUtils.init(service.getAddress(), service.getPort())) {
                return;
            }
            nodes.remove(service);
        });

        if(JedisUtils.getJedisPool() != null) {
            JedisUtils.set("test", "test");
            JedisUtils.get("test");
        }

    }

    public void watchSvr(){
        try {
            ServiceHealthCache serviceHealthCache = ServiceHealthCache.newCache(healthClient, redisService);
            serviceHealthCache.addListener(map -> {
                logger.info("ServiceHealthCache change event");
                getHealthService(redisService);
            });
            serviceHealthCache.start();
        } catch (Exception e) {
            logger.info("ServiceHealthCache e: {}", e);
        }
    }
}
