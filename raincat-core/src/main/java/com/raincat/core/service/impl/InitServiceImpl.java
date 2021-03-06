/*
 *
 * Copyright 2017-2018 549477611@qq.com(xiaoyu)
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.raincat.core.service.impl;

import com.raincat.common.config.TxConfig;
import com.raincat.common.enums.CompensationCacheTypeEnum;
import com.raincat.common.enums.SerializeProtocolEnum;
import com.raincat.common.exception.TransactionRuntimeException;
import com.raincat.common.holder.LogUtil;
import com.raincat.common.holder.ServiceBootstrap;
import com.raincat.common.serializer.KryoSerializer;
import com.raincat.common.serializer.ObjectSerializer;
import com.raincat.core.compensation.TxCompensationService;
import com.raincat.core.disruptor.publisher.TxTransactionEventPublisher;
import com.raincat.core.helper.SpringBeanUtils;
import com.raincat.core.netty.NettyClientService;
import com.raincat.core.service.InitService;
import com.raincat.core.spi.TransactionRecoverRepository;
import com.raincat.core.spi.repository.JdbcTransactionRecoverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

/**
 * tx transaction init.
 * @author xiaoyu
 */
@Component
public class InitServiceImpl implements InitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InitServiceImpl.class);

    private final NettyClientService nettyClientService;

    private final TxCompensationService txCompensationService;

    private final TxTransactionEventPublisher txTransactionEventPublisher;

    @Autowired
    public InitServiceImpl(final NettyClientService nettyClientService,
                           final TxCompensationService txCompensationService,
                           final TxTransactionEventPublisher txTransactionEventPublisher) {
        this.nettyClientService = nettyClientService;
        this.txCompensationService = txCompensationService;
        this.txTransactionEventPublisher = txTransactionEventPublisher;
    }

    @Override
    public void initialization(final TxConfig txConfig) {
        try {
            loadSpi(txConfig);
            nettyClientService.start(txConfig);
            txCompensationService.start(txConfig);
            txTransactionEventPublisher.start(txConfig.getBufferSize());
        } catch (Exception e) {
            throw new TransactionRuntimeException("tx transaction ex:{}：" + e.getMessage());
        }
        LogUtil.info(LOGGER, () -> "tx transaction init success！");

    }

    /**
     * load spi.
     *
     * @param txConfig {@linkplain TxConfig}
     */
    private void loadSpi(final TxConfig txConfig) {
        //spi  serialize
        final SerializeProtocolEnum serializeProtocolEnum
                = SerializeProtocolEnum.acquireSerializeProtocol(txConfig.getSerializer());
        final ServiceLoader<ObjectSerializer> objectSerializers
                = ServiceBootstrap.loadAll(ObjectSerializer.class);
        final ObjectSerializer serializer =
                StreamSupport.stream(objectSerializers.spliterator(), false)
                        .filter(s -> Objects.equals(s.getScheme(), serializeProtocolEnum.getSerializeProtocol()))
                        .findFirst().orElse(new KryoSerializer());

        //spi  RecoverRepository support
        final CompensationCacheTypeEnum compensationCacheTypeEnum
                = CompensationCacheTypeEnum.acquireCompensationCacheType(txConfig.getCompensationCacheType());

        final ServiceLoader<TransactionRecoverRepository> recoverRepositories
                = ServiceBootstrap.loadAll(TransactionRecoverRepository.class);
        final TransactionRecoverRepository repository =
                StreamSupport.stream(recoverRepositories.spliterator(), false)
                        .filter(r -> Objects.equals(r.getScheme(), compensationCacheTypeEnum.getCompensationCacheType()))
                        .findFirst().orElse(new JdbcTransactionRecoverRepository());
        //将compensationCache实现注入到spring容器
        repository.setSerializer(serializer);
        SpringBeanUtils.getInstance().registerBean(TransactionRecoverRepository.class.getName(), repository);
    }

}
