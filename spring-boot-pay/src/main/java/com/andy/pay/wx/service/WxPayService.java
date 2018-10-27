package com.andy.pay.wx.service;

import com.andy.pay.common.enums.OrderStatusEnum;
import com.andy.pay.common.exception.ExceptionMessage;
import com.andy.pay.common.exception.ValidateException;
import com.andy.pay.common.property.AppProperties;
import com.andy.pay.common.utils.AppUtil;
import com.andy.pay.common.utils.HttpUtil;
import com.andy.pay.common.utils.ImageCodeUtil;
import com.andy.pay.common.utils.RandomUtil;
import com.andy.pay.pojos.entity.Order;
import com.andy.pay.pojos.entity.User;
import com.andy.pay.service.OrderService;
import com.andy.pay.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.impl.client.CloseableHttpClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * @author Leone
 * @since 2018-05-22
 **/
@Slf4j
@Service
public class WxPayService {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    @Resource
    private static AppProperties appProperties;

    /**
     * App支付
     *
     * @param request
     * @param orderId
     */
    public void appPay(HttpServletRequest request, Long orderId) {
        Order order = orderService.findOne(orderId);
        if (order.getStatus() != OrderStatusEnum.CREATE.getStatus()) {
            log.error("create order failed order status error orderId:{}", orderId);
            return;
        }
        String spbill_create_ip = AppUtil.getIpAddress(request);
        if (!AppUtil.isIp(spbill_create_ip)) {
            spbill_create_ip = "127.0.0.1";
        }

        //微信支付是个必须要传入的参数
        Map<String, String> params = new HashMap<>();
        params.put("appid", appProperties.getWx().getApp_id());                 //appId
        params.put("mch_id", appProperties.getWx().getMch_id());                //微信支付商户号
        params.put("nonce_str", RandomUtil.getStr(12));                      //随机字符串
        params.put("body", "App weChat pay!");                               //商品描述
        params.put("out_trade_no", order.getOutTradeNo());                  //商户订单号
        params.put("total_fee", order.getTotalFee().toString());             //总金额(分)
        params.put("spbill_create_ip", spbill_create_ip);                 //订单生成的机器IP，指用户浏览器端IP
        params.put("notify_url", appProperties.getWx().getNotify_url());        //回调url
        params.put("trade_type", "APP");                                     // 交易类型:JS_API=公众号支付、NATIVE=扫码支付、APP=app支付
        String sign = AppUtil.createSign(params, appProperties.getWx().getApi_key());
        params.put("sign", "sign");
        String xmlData = AppUtil.mapToXml(params);
        log.info("xmlData:{}", xmlData);
        String wxRetXmlData = HttpUtil.sendPostXml(appProperties.getWx().getCreate_order(), xmlData, null);
        Map retData = AppUtil.xmlToMap(wxRetXmlData);
        log.info("微信返回信息:{}", retData);
    }

    /**
     * 微信扫码支付传入金额为分
     *
     * @param totalFee
     * @param response
     * @param request
     * @return
     * @throws Exception
     */
    public boolean qrCodePay(String totalFee, HttpServletResponse response, HttpServletRequest request) {
        String nonce_str = RandomUtil.getStr(12);
        String outTradeNo = 1 + RandomUtil.getNum(11);
        String spbill_create_ip = AppUtil.getIpAddress(request);
        if (!AppUtil.isIp(spbill_create_ip)) {
            spbill_create_ip = "127.0.0.1";
        }
        Map<String, String> params = new TreeMap<>();
        params.put("appid", appProperties.getWx().getApp_id());
        params.put("mch_id", appProperties.getWx().getMch_id());
        params.put("nonce_str", nonce_str);
        params.put("body", "微信扫码支付");
        params.put("out_trade_no", outTradeNo);
        params.put("total_fee", totalFee);
        params.put("spbill_create_ip", spbill_create_ip);
        params.put("notify_url", appProperties.getWx().getRefund_url());
        params.put("trade_type", "NATIVE");
        String sign = AppUtil.createSign(params, appProperties.getWx().getApi_key());
        params.put("sign", sign);
        String requestXml = AppUtil.mapToXml(params);
        String responseXml = HttpUtil.sendPostXml(appProperties.getWx().getCreate_order(), requestXml, null);
        Map<String, String> responseMap = AppUtil.xmlToMap(responseXml);
        //return_code为微信返回的状态码，SUCCESS表示成功，return_msg 如非空，为错误原因 签名失败 参数格式校验错误
        if ("SUCCESS".equals(responseMap.get("return_code")) && "SUCCESS".equals(responseMap.get("result_code"))) {
            log.info("wx pre pay success response:{}", responseMap);
            ImageCodeUtil.createQRCode(responseMap.get("code_url"), response);
            // 保存订单信息
            return true;
        }
        log.error("wx pre pay error response:{}", responseMap);
        return false;
    }

    /**
     * 微信支付回调
     *
     * @param request
     * @param response
     * @throws IOException
     */
    public void notifyOrder(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String resXml = "<xml><return_code><![CDATA[SUCCESS]]></return_code><return_msg><![CDATA[OK]]></return_msg></xml>";
        BufferedOutputStream out = new BufferedOutputStream(response.getOutputStream());
        out.write(resXml.getBytes());
        out.flush();
        out.close();
        log.info("wx notify success");
        // 保存流水信息
    }

    /**
     * 微信退款
     *
     * @param xmlData
     * @return
     * @throws Exception
     */
    public static boolean wxRefund(String xmlData) throws Exception {
        CloseableHttpClient httpClient = HttpUtil.sslHttpsClient(appProperties.getWx().getCertificate(), appProperties.getWx().getApi_key());
        String xmlResponse = HttpUtil.sendSslXmlPost(appProperties.getWx().getRefund_url(), xmlData, null, httpClient);
        Map<String, String> mapData = AppUtil.xmlToMap(xmlResponse);
        //return_code为微信返回的状态码，SUCCESS表示申请退款成功，return_msg 如非空，为错误原因 签名失败 参数格式校验错误
        if ("SUCCESS".equalsIgnoreCase(mapData.get("return_code"))) {
            log.info("wx refund success response:{}", mapData);
            // 修改订单状态为退款
            return true;
        }
        log.error("wx refund error response:{}", mapData);
        return false;
    }

    /**
     * 微信小程序支付
     *
     * @param orderId
     * @param request
     */
    public Map xcxPay(Long orderId, HttpServletRequest request) {
        Order order = orderService.findOne(orderId);
        User user = userService.findOne(order.getUserId());
        String nonce_str = RandomUtil.getNum(24);
        String outTradeNo = 1 + RandomUtil.getNum(11);
        String spbill_create_ip = AppUtil.getIpAddress(request);
        if (!AppUtil.isIp(spbill_create_ip)) {
            spbill_create_ip = "127.0.0.1";
        }
        SortedMap<String, String> reqMap = new TreeMap<>();
        reqMap.put("appid", appProperties.getWx().getApp_id());
        reqMap.put("mch_id", appProperties.getWx().getMch_id());
        reqMap.put("nonce_str", nonce_str);
        reqMap.put("body", "小程序支付");
        reqMap.put("out_trade_no", outTradeNo);
        reqMap.put("total_fee", order.getTotalFee().toString());
        reqMap.put("spbill_create_ip", spbill_create_ip);
        reqMap.put("notify_url", appProperties.getWx().getNotify_url());
        reqMap.put("trade_type", appProperties.getWx().getTrade_type());
        reqMap.put("openid", user.getOpenid());
        String sign = AppUtil.createSign(reqMap, appProperties.getWx().getApi_key());
        reqMap.put("sign", sign);
        String xml = AppUtil.mapToXml(reqMap);
        String result = HttpUtil.sendPostXml(appProperties.getWx().getCreate_order(), xml, null);
        Map<String, String> resData = AppUtil.xmlToMap(result);
        log.info("resData:{}", resData);
        if ("SUCCESS".equals(resData.get("return_code"))) {
            Map<String, String> resultMap = new LinkedHashMap<>();
            //返回的预付单信息
            String prepay_id = resData.get("prepay_id");
            resultMap.put("appId", appProperties.getWx().getApp_id());
            resultMap.put("nonceStr", nonce_str);
            resultMap.put("package", "prepay_id=" + prepay_id);
            resultMap.put("signType", "MD5");
            resultMap.put("timeStamp", RandomUtil.getDateStr(14));
            String paySign = AppUtil.createSign(resultMap, appProperties.getWx().getApi_key());
            resultMap.put("paySign", paySign);
            log.info("return data:{}", resultMap);
            return resultMap;
        } else {
            throw new ValidateException(ExceptionMessage.WEI_XIN_PAY_FAIL);
        }

    }
}
