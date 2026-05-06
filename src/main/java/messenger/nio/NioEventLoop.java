package messenger.nio;

import java.io.IOException;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Главный NIO event loop на базе Selector.
 * Обрабатывает все сетевые события в одном потоке.
 *
 * Thread-safety:
 * - register() может быть вызван из любого потока
 * - execute() thread-safe для отложенных задач
 */
public final class NioEventLoop implements Runnable, AutoCloseable {

    private final Selector selector;
    private final Map<SelectableChannel, ChannelHandler> handlers;
    private final Thread eventLoopThread;
    private final Queue<Runnable> pendingTasks;
    private volatile boolean running;

    public NioEventLoop() throws IOException {
        this.selector = Selector.open();
        this.handlers = new ConcurrentHashMap<>();
        this.eventLoopThread = new Thread(this, "NIO-EventLoop");
        this.pendingTasks = new ConcurrentLinkedQueue<>();
        this.running = false;
    }

    /**
     * Получить Selector для обновления interestOps.
     * ВАЖНО: Используйте только из NIO потока или через execute()!
     */
    public Selector getSelector() {
        return selector;
    }

    /**
     * Запуск event loop.
     *
     * @throws IllegalStateException если уже запущен
     */
    public void start() {
        if (running) {
            throw new IllegalStateException("EventLoop already running");
        }
        running = true;
        eventLoopThread.start();
        System.out.println("[NIO] Event loop started on thread: " + eventLoopThread.getName());
    }

    /**
     * Регистрация канала с обработчиком.
     * Thread-safe: можно вызывать из любого потока.
     *
     * @param channel SelectableChannel для регистрации
     * @param ops     Interest ops (OP_ACCEPT, OP_CONNECT, OP_READ, OP_WRITE)
     * @param handler Обработчик событий
     */
    public void register(SelectableChannel channel, int ops, ChannelHandler handler) throws IOException {
        channel.configureBlocking(false);

        // Если вызываем из NIO потока - регистрируем сразу
        if (Thread.currentThread() == eventLoopThread) {
            SelectionKey key = channel.register(selector, ops);
            key.attach(handler);
            handlers.put(channel, handler);
        } else {
            // Иначе - откладываем на следующий цикл
            execute(() -> {
                try {
                    SelectionKey key = channel.register(selector, ops);
                    key.attach(handler);
                    handlers.put(channel, handler);
                } catch (ClosedChannelException e) {
                    System.err.println("[NIO] Failed to register channel: " + e.getMessage());
                }
            });
            selector.wakeup(); // Прерываем select() для обработки pending task
        }
    }

    /**
     * Выполнение задачи в NIO потоке.
     * Thread-safe: можно вызывать из любого потока.
     *
     * @param task Runnable для выполнения
     */
    public void execute(Runnable task) {
        pendingTasks.offer(task);
        selector.wakeup();
    }

    /**
     * Главный цикл обработки событий.
     * Выполняется в отдельном потоке.
     */
    @Override
    public void run() {
        System.out.println("[NIO] Event loop running...");

        while (running) {
            try {
                // 1. Выполняем отложенные задачи
                processPendingTasks();

                // 2. Блокируемся до появления событий (timeout 1 сек)
                int readyChannels = selector.select(1000);

                if (readyChannels == 0) {
                    continue; // Таймаут, проверяем running и pending tasks
                }

                // 3. Обрабатываем готовые каналы
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove(); // Важно: удаляем обработанный ключ

                    if (!key.isValid()) {
                        continue;
                    }

                    ChannelHandler handler = (ChannelHandler) key.attachment();
                    if (handler == null) {
                        System.err.println("[NIO] No handler attached to key");
                        continue;
                    }

                    try {
                        // Обрабатываем события в порядке приоритета
                        if (key.isAcceptable()) {
                            handler.handleAccept(key);
                        }
                        if (key.isValid() && key.isConnectable()) {
                            handler.handleConnect(key);
                        }
                        if (key.isValid() && key.isReadable()) {
                            handler.handleRead(key);
                        }
                        if (key.isValid() && key.isWritable()) {
                            handler.handleWrite(key);
                        }
                    } catch (Exception e) {
                        System.err.println("[NIO] Error handling event: " + e.getMessage());
                        e.printStackTrace();

                        // Уведомляем handler об ошибке и закрываем канал
                        try {
                            handler.handleError(key, e);
                        } catch (Exception handlerError) {
                            System.err.println("[NIO] Error in error handler: " + handlerError.getMessage());
                        }
                        key.cancel();
                    }
                }

            } catch (IOException e) {
                System.err.println("[NIO] Selector error: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("[NIO] Unexpected error in event loop: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("[NIO] Event loop stopped");
    }

    /**
     * Обработка отложенных задач.
     */
    private void processPendingTasks() {
        Runnable task;
        int processed = 0;

        while ((task = pendingTasks.poll()) != null) {
            try {
                task.run();
                processed++;
            } catch (Exception e) {
                System.err.println("[NIO] Pending task error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (processed > 0) {
            System.out.println("[NIO] Processed " + processed + " pending tasks");
        }
    }

    /**
     * Остановка event loop.
     * Блокирует до завершения потока (max 5 сек).
     */
    public void stop() {
        if (!running) {
            return;
        }

        System.out.println("[NIO] Stopping event loop...");
        running = false;
        selector.wakeup();

        try {
            eventLoopThread.join(5000);
            if (eventLoopThread.isAlive()) {
                System.err.println("[NIO] Event loop thread did not stop in time");
                eventLoopThread.interrupt();
            }
            selector.close();
            System.out.println("[NIO] Event loop stopped successfully");
        } catch (Exception e) {
            System.err.println("[NIO] Error stopping event loop: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }
}