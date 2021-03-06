package com.zhang.order.service.impl;

import com.zhang.order.dto.OrderDTO;
import com.zhang.order.entity.OrderDetail;
import com.zhang.order.entity.OrderMaster;
import com.zhang.order.enums.OrderStatusEnum;
import com.zhang.order.enums.PayStatusEnum;
import com.zhang.order.enums.ResultEnum;
import com.zhang.order.exception.OrderException;
import com.zhang.order.repository.OrderDetailRepository;
import com.zhang.order.repository.OrderMasterRepository;
import com.zhang.order.service.OrderService;
import com.zhang.order.utils.KeyUtil;
import com.zhang.product.client.ProductClient;
import com.zhang.product.common.DecreaseStockInput;
import com.zhang.product.common.ProductInfoOutput;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;


/**
 * @author codingzx
 * @description 订单入库功能
 * 客户端：
 * 1.参数校验
 * 2.查询购物车中所有商品 -  构造商品idList 调用服务端接口查询所有商品productInfoList
 * 3.计算价格
 * 循环购物车商品list—》ordertail  和 productInfoList -》productInfo，新建订单详情ordertail
 * 当id相等 计算价格 ，把productInfo复制到订单详情ordertail
 * 订单详情入库
 * 4.扣减库存
 * 调用服务端接口
 * 设置订单状态
 * 订单入库
 * <p>
 * <p>
 * <p>
 * <p>
 * <p>
 * 服务端：
 * 先遍历购物车对象，通过遍历找到每个商品
 * 判断商品是否存在
 * 更新库存：商品库存-购物车商品数量
 * @date 2020/2/8 17:26
 */
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderDetailRepository orderDetailRepository;
    @Autowired
    private OrderMasterRepository orderMasterRepository;

    @Autowired
    private ProductClient productClient;

    @Override
    @Transactional
    public OrderDTO create(OrderDTO orderDTO) {

        //读redis（加分布式锁防止并发错误）
        //减库存并重新将新值重新设置进redis（加分布式锁防止并发错误）
        //订单入库异常，需要手动回滚Redis

        //库存在Redis保存
        //收到请求Redis判断是否库存充足，减掉Redis库存
        //订单服务创建订单写入数据库，并发送消息

        String orderId = KeyUtil.getUniqueKey();
        // 1. 查询商品信息
        //lam表达式 获取 orderDto.orderList里面所有的id，并返回List
        List<String> productIdList = orderDTO.getOrderDetailList().stream()
                .map(OrderDetail::getProductId).collect(Collectors.toList());

        //计时 调用他人代码
        List<ProductInfoOutput> productInfoList = productClient.listForOrder(productIdList);

        //  * 2.  计算总价
        BigDecimal orderAmout = new BigDecimal(BigInteger.ZERO);
        for (OrderDetail orderDetail : orderDTO.getOrderDetailList()) {
            //先循环订单中所有商品
            for (ProductInfoOutput productInfo : productInfoList) {
                //在循环订单里面有的商品信息
                if (productInfo.getProductId().equals(orderDetail.getProductId())) {
                    //判斷是取到商品的價格  总价=单价*数量
                    orderAmout = productInfo.getProductPrice().multiply(new BigDecimal(orderDetail.getProductQuantity())).add(orderAmout);
                    //前端只传商品id和商品数量 其他属性需要拷贝
                    BeanUtils.copyProperties(productInfo, orderDetail);
                    orderDetail.setOrderId(orderId);
                    orderDetail.setDetailId(KeyUtil.getUniqueKey());
                    //订单详情入库
                    orderDetailRepository.save(orderDetail);
                }
            }
        }

        //  * 3. 扣库存  lam表达式获取对象  获取订单里面所有的cartDto对象 进行扣减库存
        List<DecreaseStockInput> cartDtoList = orderDTO.getOrderDetailList().stream()
                .map(e -> new DecreaseStockInput(e.getProductId(), e.getProductQuantity())).collect(Collectors.toList());
        productClient.decreaseStock(cartDtoList);

        //  * 4.订单入库
        OrderMaster orderMaster = new OrderMaster();
        orderMaster.setUpdateTime(new Date());
        orderMaster.setCreateTime(new Date());
        orderDTO.setOrderId(orderId);
        BeanUtils.copyProperties(orderDTO, orderMaster);
        orderMaster.setOrderAmount(orderAmout);
        orderMaster.setOrderStatus(OrderStatusEnum.New.getCode());
        orderMaster.setPayStatus(PayStatusEnum.WAIT.getCode());
        orderMasterRepository.save(orderMaster);
        return orderDTO;
    }

    @Override
    @Transactional
    public OrderDTO finish(String orderId) {
        //1.查询订单状态
        Optional<OrderMaster> orderMasterOptional = orderMasterRepository.findById(orderId);
        if (!orderMasterOptional.isPresent()) {
            throw new OrderException(ResultEnum.ORDER_NOT_EXIST);
        }
        //2.判断订单状态
        OrderMaster orderMaster = orderMasterOptional.get();
        if (orderMaster.getOrderStatus() != OrderStatusEnum.New.getCode()) {
            throw new OrderException(ResultEnum.ORDER_STATUS_ERROR);
        }
        //3.修改订单状态为完结
        orderMaster.setOrderStatus(OrderStatusEnum.FINISHED.getCode());
        orderMasterRepository.save(orderMaster);

        List<OrderDetail> orderDetailList = orderDetailRepository.findByOrderId(orderId);
        if (CollectionUtils.isEmpty(orderDetailList)) {
            throw new OrderException(ResultEnum.ORDER_DETAIL_NOT_EXIST);
        }
        OrderDTO orderDTO=new OrderDTO();
        orderDTO.setOrderDetailList(orderDetailList);
        BeanUtils.copyProperties(orderMaster,orderDTO);
        return orderDTO;
    }

}
