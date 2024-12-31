package com.ws.test;

public class TestMain {

    public static void main(String[] args) {
        Test t = new Test();
        t.setName("");
        t.setTestField("");
        t.setMob(1);

        Test t2 = Test.builder().testField("").mob(1).build();
    }
}
