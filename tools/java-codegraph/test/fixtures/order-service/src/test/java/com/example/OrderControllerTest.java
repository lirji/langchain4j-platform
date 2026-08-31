package com.example;

import com.example.OrderController;
import com.example.OrderService;

public class OrderControllerTest {
    void invokesService() {
        new OrderController(new OrderService()).getOrder();
    }
}
