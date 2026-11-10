package com.flowershop.order.client;

import com.flowershop.common.api.ApiResponse;
import com.flowershop.common.client.RemoteServiceException;
import com.flowershop.common.client.ServiceClientFactory;
import com.flowershop.common.client.ServiceTokenClient;
import com.flowershop.common.error.BusinessException;
import com.flowershop.order.config.AppConfig;
import com.flowershop.order.config.OrderProperties;
import com.flowershop.order.domain.CustomerOrder;
import com.flowershop.order.error.OrderErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

import java.util.List;

/**
 * Lấy địa chỉ trong sổ địa chỉ của khách, bằng service token (customer:read).
 * customerId lấy từ TOKEN CỦA KHÁCH: khách không thể dùng addressId của người khác.
 */
@Slf4j
@Component
public class CustomerClient {

    record Address(Long id, Long customerId, String recipientName, String recipientPhone, String addressLine,
                   String ward, String province, String note) {
    }

    interface AddressApi {
        @GetExchange("/api/v1/customers/{customerId}/addresses")
        ApiResponse<List<Address>> list(@PathVariable Long customerId);
    }

    private final AddressApi api;

    public CustomerClient(ServiceClientFactory clients, OrderProperties props, ServiceTokenClient serviceToken) {
        this.api = clients.create(AddressApi.class, AppConfig.endpoint("customer-service", props.customer()),
                serviceToken);
    }

    public CustomerOrder.Recipient findAddress(Long customerId, Long addressId) {
        List<Address> addresses;
        try {
            ApiResponse<List<Address>> response = api.list(customerId);
            addresses = response == null || response.data() == null ? List.of() : response.data();
        } catch (RemoteServiceException e) {
            if (e.httpStatus() == 404) {
                throw new BusinessException(OrderErrorCode.ADDRESS_NOT_FOUND);
            }
            log.error("Call customer-service failed: {}", e.getMessage(), e);
            throw new BusinessException(OrderErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return addresses.stream()
                .filter(a -> addressId.equals(a.id()) && customerId.equals(a.customerId()))
                .findFirst()
                .map(a -> new CustomerOrder.Recipient(a.recipientName(), a.recipientPhone(), a.addressLine(),
                        a.ward(), a.province(), a.note()))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ADDRESS_NOT_FOUND));
    }
}
