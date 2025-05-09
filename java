// BattlePassServer.java
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.*;
import com.zaxxer.hikari.HikariDataSource;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class BattlePassServer {
    private static final int PORT = 8888;
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    
    // Database configuration
    private static final HikariDataSource dataSource = new HikariDataSource();
    private static final JedisPool redisPool = new JedisPool(new JedisPoolConfig(), "redis-host", 6379);
    
    static {
        // Database connection pool setup
        dataSource.setJdbcUrl("jdbc:mysql://db-host:3306/battlepass");
        dataSource.setUsername("user");
        dataSource.setPassword("password");
        dataSource.setMaximumPoolSize(20);
        
        initializeDatabase();
    }

    public static void main(String[] args) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Сервер запущен на порту " + PORT);
            
            while (true) {
                pool.execute(new ClientHandler(serverSocket.accept()));
            }
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(
                    socket.getOutputStream(), true)) {

                String input;
                while ((input = in.readLine()) != null) {
                    processCommand(input, out);
                }
            } catch (IOException e) {
                System.out.println("Ошибка: " + e.getMessage());
            }
        }

        private void processCommand(String command, PrintWriter out) {
            String[] parts = command.split(":");
            if (parts.length < 2) return;

            int playerId = Integer.parseInt(parts[1]);
            
            try {
                BattlePass bp = loadPlayerData(playerId);
                
                switch(parts[0]) {
                    case "LOAD":
                        sendPlayerData(out, playerId, bp);
                        break;
                    case "SAVE":
                        savePlayerData(playerId, bp);
                        break;
                    case "EVENT":
                        handleEvent(playerId, bp, parts[2], Integer.parseInt(parts[3]));
                        break;
                    case "PING":
                        out.println("PONG");
                        break;
                }
            } catch (Exception e) {
                out.println("ERROR: " + e.getMessage());
            }
        }

        private BattlePass loadPlayerData(int playerId) throws Exception {
            // Check Redis cache first
            try (var jedis = redisPool.getResource()) {
                String cachedData = jedis.get("bp:" + playerId);
                if (cachedData != null) {
                    return deserialize(cachedData.getBytes());
                }
            }

            // Database fallback
            try (Connection conn = dataSource.getConnection()) {
                PreparedStatement stmt = conn.prepareStatement(
                    "SELECT data FROM passes WHERE player_id = ?");
                stmt.setInt(1, playerId);
                
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    BattlePass bp = deserialize(rs.getBytes("data"));
                    
                    // Update cache
                    try (var jedis = redisPool.getResource()) {
                        jedis.setex("bp:" + playerId, 3600, new String(serialize(bp)));
                    }
                    return bp;
                }
            }
            
            // New player
            BattlePass newBp = new BattlePass(loadDefaultLevels());
            savePlayerData(playerId, newBp);
            return newBp;
        }

        private void sendPlayerData(PrintWriter out, int playerId, BattlePass bp) {
            out.printf("LEVEL:%d:%d%n", playerId, bp.getCurrentLevel());
            out.printf("XP:%d:%d%n", playerId, bp.getCurrentXp());
        }

        private void savePlayerData(int playerId, BattlePass bp) {
            try (Connection conn = dataSource.getConnection()) {
                byte[] data = serialize(bp);
                
                PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO passes (player_id, data) VALUES (?, ?) " +
                    "ON DUPLICATE KEY UPDATE data = ?");
                stmt.setInt(1, playerId);
                stmt.setBytes(2, data);
                stmt.setBytes(3, data);
                stmt.executeUpdate();
                
                // Update cache
                try (var jedis = redisPool.getResource()) {
                    jedis.setex("bp:" + playerId, 3600, new String(data));
                }
            } catch (SQLException e) {
                throw new RuntimeException("Database error", e);
            }
        }

        private void handleEvent(int playerId, BattlePass bp, String eventType, int amount) {
            bp.getDailyQuests().stream()
                .filter(q -> q.getTitle().contains(eventType))
                .findFirst()
                .ifPresent(q -> {
                    q.updateProgress(amount, bp);
                    checkLevelUp(playerId, bp);
                });
        }

        private void checkLevelUp(int playerId, BattlePass bp) {
            int oldLevel = bp.getCurrentLevel();
            if (bp.checkLevelUp()) {
                try (Connection conn = dataSource.getConnection()) {
                    PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO rewards (player_id, level, reward) VALUES (?, ?, ?)");
                    stmt.setInt(1, playerId);
                    stmt.setInt(2, oldLevel + 1);
                    stmt.setString(3, bp.getCurrentReward());
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    System.out.println("Reward logging failed: " + e.getMessage());
                }
            }
        }
    }

    private static void initializeDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().executeUpdate(
                "CREATE TABLE IF NOT EXISTS passes (" +
                "player_id INT PRIMARY KEY, " +
                "data BLOB NOT NULL)");
            
            conn.createStatement().executeUpdate(
                "CREATE TABLE IF NOT EXISTS rewards (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "player_id INT NOT NULL, " +
                "level INT NOT NULL, " +
                "reward VARCHAR(255) NOT NULL, " +
                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        } catch (SQLException e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    // Serialization/deserialization methods
    private static byte[] serialize(BattlePass bp) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(bp);
            return bos.toByteArray();
        }
    }

    private static BattlePass deserialize(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (BattlePass) ois.readObject();
        }
    }

    private static List<Level> loadDefaultLevels() {
        return List.of(
            new Level(1, 100, "Начальный меч"),
            new Level(2, 300, "Зелье здоровья x3"),
            new Level(3, 600, "Золотой набор брони"),
            new Level(4, 1000, "Легендарное оружие")
        );
    }
}
