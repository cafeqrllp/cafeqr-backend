package com.restaurant.pos.concurrency;

import com.restaurant.pos.common.idempotency.IdempotencyGuard;
import com.restaurant.pos.inventory.repository.StockSnapshotRepository;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.dto.OrderResponseDto;
import com.restaurant.pos.order.exception.ConcurrentIdempotentRequestException;
import com.restaurant.pos.order.idempotency.OrderIdempotencyStore;
import com.restaurant.pos.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
public class ConcurrencyIntegrationTest {

    @Autowired
    private IdempotencyGuard idempotencyGuard;

    @Autowired
    private OrderService orderService;

    @MockBean
    private com.restaurant.pos.order.repository.OrderRepository orderRepository;

    @MockBean
    private com.restaurant.pos.sequence.service.DocumentSequenceService sequenceService;

    @MockBean
    private com.restaurant.pos.invoice.repository.InvoiceRepository invoiceRepository;

    @MockBean
    private com.restaurant.pos.order.repository.PaymentRepository paymentRepository;

    @MockBean
    private OrderIdempotencyStore orderIdempotencyStore;

    @Autowired
    private StockSnapshotRepository stockSnapshotRepository;

    @Test
    void verifyRetryableOptimisticLockingOnUpdateOrderStatus() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order();
        order.setId(orderId);
        order.setOrderStatus("DRAFT");
        order.setClientId(UUID.randomUUID());
        order.setOrgId(UUID.randomUUID());

        // Mock both findByIdAndClientId and findByIdAndClientIdAndOrgId to fail-safe against test client context
        when(orderRepository.findByIdAndClientId(eq(orderId), any()))
                .thenReturn(java.util.Optional.of(order));
        when(orderRepository.findByIdAndClientIdAndOrgId(eq(orderId), any(), any()))
                .thenReturn(java.util.Optional.of(order));

        when(sequenceService.generateNextSequence(any()))
                .thenReturn("INV-TEST-SEQUENCE");

        when(orderRepository.save(any(Order.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Order.class, orderId))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Execute updateOrderStatus which is decorated with @Retryable
        Order result = orderService.updateOrderStatus(orderId, OrderStatus.CONFIRMED);

        // Assert that it successfully retried and returned the updated order
        assertThat(result.getOrderStatus()).isEqualTo("CONFIRMED");
        verify(orderRepository, times(2)).save(any(Order.class)); // 1 failure + 1 retry success
    }

    @Test
    void verifyPessimisticWriteLockAnnotationOnStockSnapshotQuery() throws NoSuchMethodException {
        // Reflectively verify that the Pessimistic Lock annotation is correctly declared on the repository query
        Method method = StockSnapshotRepository.class.getMethod(
                "findByWarehouseIdAndProductIdAndVariantId", 
                UUID.class, UUID.class, UUID.class
        );
        
        org.springframework.data.jpa.repository.Lock lockAnnotation = 
                method.getAnnotation(org.springframework.data.jpa.repository.Lock.class);
        
        assertThat(lockAnnotation).isNotNull();
        assertThat(lockAnnotation.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void verifyConcurrentIdempotencyGuardBlocksDuplicates() throws InterruptedException, ExecutionException {
        UUID orderId = UUID.randomUUID();
        String idempotencyKey = "test-concurrent-key-" + UUID.randomUUID();
        
        // Mock the idempotency store behavior to simulate atomic lock wins and concurrent failures
        AtomicBoolean lockAcquired = new AtomicBoolean(false);
        when(orderIdempotencyStore.acquireLock(anyString(), any()))
                .thenAnswer(invocation -> !lockAcquired.getAndSet(true));
        
        when(orderIdempotencyStore.get(anyString(), any()))
                .thenReturn(null);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(1);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        Callable<OrderResponseDto> task = () -> {
            latch.await(); // wait for latch to fire simultaneously
            return idempotencyGuard.execute("testAction", orderId, idempotencyKey, OrderResponseDto.class, () -> {
                try {
                    Thread.sleep(100); // simulate slow database work inside lock
                } catch (InterruptedException ignored) {}
                OrderResponseDto o = new OrderResponseDto();
                o.setId(orderId);
                return o;
            });
        };
        
        // Submit tasks
        Future<OrderResponseDto> f1 = executor.submit(task);
        Future<OrderResponseDto> f2 = executor.submit(task);
        Future<OrderResponseDto> f3 = executor.submit(task);
        
        latch.countDown(); // fire simultaneously
        
        // Check futures
        checkFuture(f1, successCount, failureCount);
        checkFuture(f2, successCount, failureCount);
        checkFuture(f3, successCount, failureCount);
        
        executor.shutdown();
        
        // Assert exactly 1 request succeeded and remaining failed with active lock conflict
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(2);
    }

    private void checkFuture(Future<OrderResponseDto> future, AtomicInteger success, AtomicInteger failure) {
        try {
            OrderResponseDto order = future.get();
            if (order != null) {
                success.incrementAndGet();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ConcurrentIdempotentRequestException) {
                failure.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
