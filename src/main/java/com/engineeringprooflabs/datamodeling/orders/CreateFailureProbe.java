package com.engineeringprooflabs.datamodeling.orders;

interface CreateFailureProbe {

    void insertHeaderThenFail(OrderView order);
}
