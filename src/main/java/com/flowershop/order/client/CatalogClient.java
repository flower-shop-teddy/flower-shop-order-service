package com.flowershop.order.client;

import com.flowershop.common.api.ApiResponse;
import com.flowershop.common.client.RemoteServiceException;
import com.flowershop.common.client.ServiceClientFactory;
import com.flowershop.common.error.BusinessException;
import com.flowershop.order.config.AppConfig;
import com.flowershop.order.config.OrderProperties;
import com.flowershop.order.error.OrderErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

import java.math.BigDecimal;

/** Giá và tên sản phẩm lấy từ catalog lúc đặt (nguồn sự thật về giá), rồi chụp vào đơn. */
@Slf4j
@Component
public class CatalogClient {

    public record Product(String sku, String name, BigDecimal unitPrice, String currency) {
    }

    record FlowerInfo(String sku, String name, BigDecimal unitPrice, String currency, String status) {
    }

    interface FlowerApi {
        @GetExchange("/api/v1/flowers/sku/{sku}")
        ApiResponse<FlowerInfo> getBySku(@PathVariable String sku);
    }

    private final FlowerApi api;

    public CatalogClient(ServiceClientFactory clients, OrderProperties props) {
        // Xem sản phẩm là API public: không cần token
        this.api = clients.create(FlowerApi.class, AppConfig.endpoint("catalog-service", props.catalog()), null);
    }

    public Product getActive(String sku) {
        FlowerInfo flower;
        try {
            ApiResponse<FlowerInfo> response = api.getBySku(sku);
            flower = response == null ? null : response.data();
        } catch (RemoteServiceException e) {
            if (e.hasCode("CAT-404-001")) {
                throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE, "Sản phẩm " + sku + " không tồn tại");
            }
            log.error("Call catalog-service failed: {}", e.getMessage(), e);
            throw new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (flower == null || flower.unitPrice() == null) {
            throw new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (!"ACTIVE".equals(flower.status())) {
            throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE, "Sản phẩm " + sku + " đã ngừng bán");
        }
        return new Product(flower.sku(), flower.name(), flower.unitPrice(), flower.currency());
    }
}
