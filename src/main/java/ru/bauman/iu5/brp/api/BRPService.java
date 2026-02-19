package ru.bauman.iu5.brp.api;

public class BRPService {
    private static volatile BRPService instance;

    // Private constructor
    private BRPService() {}

    // Thread-safe singleton
    public static BRPService getInstance() {
        if (instance == null) {
            synchronized (BRPService.class) {
                if (instance == null) {
                    instance = new BRPService();
                    System.out.println("BRPService singleton created");
                }
            }
        }
        return instance;
    }

    // Пример API метода
    public String getNodeStatus() {
        return "Node ID: BRP-001 | Status: ONLINE | Ring: CONNECTED";
    }

    public String processMessage(String message) {
        return "API Response: " + message.toUpperCase();
    }
}
