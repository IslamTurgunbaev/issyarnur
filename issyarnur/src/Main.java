import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class Main {
    private static final int PORT = 8080;
    private static final Path DATA_FILE = Path.of("data", "fastrent.properties");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Object DATA_LOCK = new Object();
    private static final Store STORE = loadStore();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", Main::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("FastRent is running at http://localhost:" + PORT);
        System.out.println("Demo admin: admin@fastrent.ru / FastRent2026!");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (method.equals("GET") && path.equals("/")) {
                sendFile(exchange, "index.html", "text/html; charset=utf-8");
            } else if (method.equals("GET") && path.equals("/style.css")) {
                sendFile(exchange, "style.css", "text/css; charset=utf-8");
            } else if (method.equals("GET") && path.equals("/app.js")) {
                sendFile(exchange, "app.js", "text/javascript; charset=utf-8");
            } else if (method.equals("GET") && path.equals("/api/listings")) {
                getListings(exchange);
            } else if (method.equals("GET") && path.equals("/api/me")) {
                User user = currentUser(exchange);
                sendJson(exchange, 200, "{\"user\":" + (user == null ? "null" : userJson(user)) + "}");
            } else if (method.equals("GET") && path.equals("/api/bookings")) {
                getBookings(exchange);
            } else if (method.equals("POST") && path.equals("/api/register")) {
                register(exchange);
            } else if (method.equals("POST") && path.equals("/api/login")) {
                login(exchange);
            } else if (method.equals("POST") && path.equals("/api/logout")) {
                logout(exchange);
            } else if (method.equals("POST") && path.equals("/api/bookings")) {
                createBooking(exchange);
            } else if (method.equals("POST") && path.matches("/api/bookings/[^/]+/cancel")) {
                cancelBooking(exchange, path.split("/")[3]);
            } else if (method.equals("GET") && path.equals("/api/admin/listings")) {
                requireAdmin(exchange);
                sendListings(exchange, true);
            } else if (method.equals("POST") && path.equals("/api/admin/listings")) {
                createListing(exchange);
            } else if (method.equals("POST") && path.matches("/api/admin/listings/[^/]+/toggle")) {
                toggleListing(exchange, path.split("/")[4]);
            } else {
                sendJson(exchange, 404, error("Страница не найдена"));
            }
        } catch (ApiException exception) {
            sendJson(exchange, exception.status, error(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, error(exception.getMessage() == null ? "Проверьте введённые данные" : exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, error("Внутренняя ошибка сервера"));
        } finally {
            exchange.close();
        }
    }

    private static void getListings(HttpExchange exchange) throws IOException {
        User user = currentUser(exchange);
        sendListings(exchange, user != null && user.role.equals("ADMIN"));
    }

    private static void sendListings(HttpExchange exchange, boolean includeInactive) throws IOException {
        List<Listing> listings;
        synchronized (DATA_LOCK) {
            listings = STORE.listings.stream().filter(item -> includeInactive || item.active).toList();
        }
        StringBuilder json = new StringBuilder("{\"listings\":[");
        for (int i = 0; i < listings.size(); i++) {
            if (i > 0) json.append(',');
            json.append(listingJson(listings.get(i)));
        }
        sendJson(exchange, 200, json.append("]}").toString());
    }

    private static void register(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        String name = required(form, "name").trim();
        String email = required(form, "email").trim().toLowerCase();
        String password = required(form, "password");
        if (name.length() < 2 || name.length() > 60) throw new ApiException(400, "Имя должно содержать от 2 до 60 символов");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new ApiException(400, "Введите корректный email");
        if (password.length() < 8 || password.length() > 128) throw new ApiException(400, "Пароль должен содержать от 8 до 128 символов");

        User user = new User(UUID.randomUUID().toString(), email, name, hashPassword(password), "USER");
        synchronized (DATA_LOCK) {
            if (STORE.users.containsKey(email)) throw new ApiException(409, "Аккаунт с таким email уже существует");
            STORE.users.put(email, user);
            saveStore();
        }
        createSession(exchange, user);
        sendJson(exchange, 201, "{\"user\":" + userJson(user) + "}");
    }

    private static void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        String email = required(form, "email").trim().toLowerCase();
        String password = required(form, "password");
        User user;
        synchronized (DATA_LOCK) {
            user = STORE.users.get(email);
        }
        if (user == null || !verifyPassword(password, user.passwordHash)) throw new ApiException(401, "Неверный email или пароль");
        createSession(exchange, user);
        sendJson(exchange, 200, "{\"user\":" + userJson(user) + "}");
    }

    private static void logout(HttpExchange exchange) throws IOException {
        String token = sessionToken(exchange);
        if (token != null) SESSIONS.remove(token);
        exchange.getResponseHeaders().add("Set-Cookie", "fastrent_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private static void createBooking(HttpExchange exchange) throws IOException {
        User user = requireUser(exchange);
        Map<String, String> form = readForm(exchange);
        String listingId = required(form, "listingId");
        LocalDate checkIn = LocalDate.parse(required(form, "checkIn"));
        LocalDate checkOut = LocalDate.parse(required(form, "checkOut"));
        int guests = parseInt(required(form, "guests"), "Количество гостей");
        if (!checkIn.isAfter(LocalDate.now())) throw new ApiException(400, "Дата заезда должна быть в будущем");
        if (!checkOut.isAfter(checkIn)) throw new ApiException(400, "Дата выезда должна быть позже даты заезда");
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > 90) throw new ApiException(400, "Бронирование возможно максимум на 90 ночей");

        synchronized (DATA_LOCK) {
            Listing listing = findListing(listingId);
            if (listing == null || !listing.active) throw new ApiException(404, "Квартира больше недоступна");
            if (guests < 1 || guests > listing.guests) throw new ApiException(400, "Для этой квартиры доступно от 1 до " + listing.guests + " гостей");
            boolean overlaps = STORE.bookings.stream().anyMatch(booking -> booking.listingId.equals(listingId)
                    && !booking.status.equals("CANCELLED")
                    && checkIn.isBefore(LocalDate.parse(booking.checkOut))
                    && checkOut.isAfter(LocalDate.parse(booking.checkIn)));
            if (overlaps) throw new ApiException(409, "Эти даты уже заняты. Выберите другой период");
            Booking booking = new Booking(UUID.randomUUID().toString(), user.id, listingId, checkIn.toString(),
                    checkOut.toString(), guests, nights * listing.price, "CONFIRMED");
            STORE.bookings.add(booking);
            saveStore();
            sendJson(exchange, 201, "{\"booking\":" + bookingJson(booking, listing, user) + "}");
        }
    }

    private static void getBookings(HttpExchange exchange) throws IOException {
        User user = requireUser(exchange);
        List<String> bookings = new ArrayList<>();
        synchronized (DATA_LOCK) {
            for (Booking booking : STORE.bookings) {
                if (user.role.equals("ADMIN") || booking.userId.equals(user.id)) {
                    Listing listing = findListing(booking.listingId);
                    User guest = findUserById(booking.userId);
                    bookings.add(bookingJson(booking, listing, guest));
                }
            }
        }
        sendJson(exchange, 200, "{\"bookings\":[" + String.join(",", bookings) + "]}");
    }

    private static void cancelBooking(HttpExchange exchange, String bookingId) throws IOException {
        User user = requireUser(exchange);
        synchronized (DATA_LOCK) {
            Booking booking = STORE.bookings.stream().filter(item -> item.id.equals(bookingId)).findFirst().orElse(null);
            if (booking == null) throw new ApiException(404, "Бронирование не найдено");
            if (!user.role.equals("ADMIN") && !booking.userId.equals(user.id)) throw new ApiException(403, "Нет доступа к этому бронированию");
            if (booking.status.equals("CANCELLED")) throw new ApiException(409, "Бронирование уже отменено");
            booking.status = "CANCELLED";
            saveStore();
        }
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private static void createListing(HttpExchange exchange) throws IOException {
        requireAdmin(exchange);
        Map<String, String> form = readForm(exchange);
        String title = required(form, "title").trim();
        String city = required(form, "city").trim();
        String address = required(form, "address").trim();
        String image = required(form, "imageUrl").trim();
        String description = required(form, "description").trim();
        int price = parseInt(required(form, "price"), "Цена");
        int guests = parseInt(required(form, "guests"), "Количество гостей");
        int bedrooms = parseInt(required(form, "bedrooms"), "Количество комнат");
        if (title.length() > 100 || city.length() > 60 || address.length() > 120 || description.length() > 500) {
            throw new ApiException(400, "Слишком длинное значение в одном из полей");
        }
        if (!image.matches("https?://.{1,500}")) throw new ApiException(400, "Для фотографии укажите ссылку, начинающуюся с http:// или https://");
        if (price < 500 || price > 1_000_000) throw new ApiException(400, "Цена должна быть от 500 до 1 000 000 ₽ за ночь");
        if (guests < 1 || guests > 20 || bedrooms < 1 || bedrooms > 20) throw new ApiException(400, "Проверьте количество гостей и комнат");
        Listing listing = new Listing(UUID.randomUUID().toString(), title, city, address, price, guests, bedrooms, image, description, true);
        synchronized (DATA_LOCK) {
            STORE.listings.add(listing);
            saveStore();
        }
        sendJson(exchange, 201, "{\"listing\":" + listingJson(listing) + "}");
    }

    private static void toggleListing(HttpExchange exchange, String listingId) throws IOException {
        requireAdmin(exchange);
        synchronized (DATA_LOCK) {
            Listing listing = findListing(listingId);
            if (listing == null) throw new ApiException(404, "Квартира не найдена");
            listing.active = !listing.active;
            saveStore();
        }
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private static User requireUser(HttpExchange exchange) {
        User user = currentUser(exchange);
        if (user == null) throw new ApiException(401, "Войдите в аккаунт, чтобы продолжить");
        return user;
    }

    private static User requireAdmin(HttpExchange exchange) {
        User user = requireUser(exchange);
        if (!user.role.equals("ADMIN")) throw new ApiException(403, "Доступно только администратору");
        return user;
    }

    private static User currentUser(HttpExchange exchange) {
        String token = sessionToken(exchange);
        if (token == null) return null;
        Session session = SESSIONS.get(token);
        if (session == null) return null;
        if (session.expiresAt < System.currentTimeMillis()) {
            SESSIONS.remove(token);
            return null;
        }
        synchronized (DATA_LOCK) {
            return STORE.users.values().stream().filter(user -> user.id.equals(session.userId)).findFirst().orElse(null);
        }
    }

    private static String sessionToken(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("fastrent_session")) return pair[1];
        }
        return null;
    }

    private static void createSession(HttpExchange exchange, User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SESSIONS.put(token, new Session(user.id, System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));
        exchange.getResponseHeaders().add("Set-Cookie", "fastrent_session=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800");
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(16_385);
        if (body.length > 16_384) throw new ApiException(413, "Слишком большой запрос");
        Map<String, String> values = new HashMap<>();
        String raw = new String(body, StandardCharsets.UTF_8);
        if (!raw.isEmpty()) {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                values.put(key, value);
            }
        }
        return values;
    }

    private static String required(Map<String, String> form, String name) {
        String value = form.get(name);
        if (value == null || value.isBlank()) throw new ApiException(400, "Заполните все обязательные поля");
        return value;
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ApiException(400, label + ": введите целое число");
        }
    }

    private static void sendFile(HttpExchange exchange, String filename, String contentType) throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of("src", filename));
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String userJson(User user) {
        return "{\"id\":" + json(user.id) + ",\"name\":" + json(user.name) + ",\"email\":" + json(user.email)
                + ",\"role\":" + json(user.role) + "}";
    }

    private static String listingJson(Listing listing) {
        return "{\"id\":" + json(listing.id) + ",\"title\":" + json(listing.title) + ",\"city\":" + json(listing.city)
                + ",\"address\":" + json(listing.address) + ",\"price\":" + listing.price + ",\"guests\":" + listing.guests
                + ",\"bedrooms\":" + listing.bedrooms + ",\"imageUrl\":" + json(listing.imageUrl) + ",\"description\":"
                + json(listing.description) + ",\"active\":" + listing.active + "}";
    }

    private static String bookingJson(Booking booking, Listing listing, User guest) {
        return "{\"id\":" + json(booking.id) + ",\"checkIn\":" + json(booking.checkIn) + ",\"checkOut\":" + json(booking.checkOut)
                + ",\"guests\":" + booking.guests + ",\"total\":" + booking.total + ",\"status\":" + json(booking.status)
                + ",\"listing\":" + (listing == null ? "null" : listingJson(listing)) + ",\"guest\":" + (guest == null ? "null" : userJson(guest)) + "}";
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }

    private static String error(String message) {
        return "{\"error\":" + json(message) + "}";
    }

    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derive(password, salt));
    }

    private static boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split(":", 2);
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            return java.security.MessageDigest.isEqual(expected, derive(password, salt));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось проверить пароль", exception);
        }
    }

    private static Store loadStore() {
        Store store = new Store();
        if (Files.exists(DATA_FILE)) {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(DATA_FILE)) {
                properties.load(input);
                for (String key : properties.stringPropertyNames()) {
                    String[] parts = properties.getProperty(key).split("\\|", -1);
                    switch (key.substring(0, key.indexOf('.'))) {
                        case "user" -> {
                            List<String> field = decodeFields(parts);
                            User user = new User(field.get(0), field.get(1), field.get(2), field.get(3), field.get(4));
                            store.users.put(user.email, user);
                        }
                        case "listing" -> {
                            List<String> field = decodeFields(parts);
                            store.listings.add(new Listing(field.get(0), field.get(1), field.get(2), field.get(3), Integer.parseInt(field.get(4)),
                                    Integer.parseInt(field.get(5)), Integer.parseInt(field.get(6)), field.get(7), field.get(8), Boolean.parseBoolean(field.get(9))));
                        }
                        case "booking" -> {
                            List<String> field = decodeFields(parts);
                            store.bookings.add(new Booking(field.get(0), field.get(1), field.get(2), field.get(3), field.get(4),
                                    Integer.parseInt(field.get(5)), Long.parseLong(field.get(6)), field.get(7)));
                        }
                        default -> throw new IOException("Неизвестная запись в " + DATA_FILE);
                    }
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Не удалось прочитать данные FastRent из " + DATA_FILE, exception);
            }
        }
        if (store.listings.isEmpty()) seedListings(store);
        if (store.users.values().stream().noneMatch(user -> user.role.equals("ADMIN"))) {
            User admin = new User(UUID.randomUUID().toString(), "admin@fastrent.ru", "Администратор", hashPassword("FastRent2026!"), "ADMIN");
            store.users.put(admin.email, admin);
        }
        return store;
    }

    private static void seedListings(Store store) {
        store.listings.add(new Listing(UUID.randomUUID().toString(), "Светлая квартира у набережной", "Санкт-Петербург", "Петроградская сторона",
                6500, 3, 2, "https://images.unsplash.com/photo-1600210492486-724fe5c67fb0?auto=format&fit=crop&w=1200&q=85",
                "Тихая квартира с дизайнерским интерьером и видом на город. До метро — 5 минут пешком.", true));
        store.listings.add(new Listing(UUID.randomUUID().toString(), "Тёплая студия в центре", "Москва", "Тверской район",
                4900, 2, 1, "https://images.unsplash.com/photo-1600607687939-ce8a6c25118c?auto=format&fit=crop&w=1200&q=85",
                "Уютная студия рядом с кафе, музеями и прогулочными улицами. Всё необходимое для комфортной поездки.", true));
        store.listings.add(new Listing(UUID.randomUUID().toString(), "Дом с террасой у моря", "Сочи", "Центральный район",
                8900, 5, 3, "https://images.unsplash.com/photo-1600566753086-00f18fb6b3ea?auto=format&fit=crop&w=1200&q=85",
                "Просторный дом для отдыха с семьёй: собственная терраса, кухня и быстрый выход к морю.", true));
        store.listings.add(new Listing(UUID.randomUUID().toString(), "Лофт с панорамными окнами", "Казань", "Исторический центр",
                5700, 4, 2, "https://images.unsplash.com/photo-1600607687920-4e2a09cf159d?auto=format&fit=crop&w=1200&q=85",
                "Современный лофт в пешей доступности от главных достопримечательностей и набережной.", true));
    }

    private static void saveStore() {
        Properties properties = new Properties();
        int index = 0;
        for (User user : STORE.users.values()) {
            properties.setProperty("user." + index++, encodeFields(user.id, user.email, user.name, user.passwordHash, user.role));
        }
        index = 0;
        for (Listing listing : STORE.listings) {
            properties.setProperty("listing." + index++, encodeFields(listing.id, listing.title, listing.city, listing.address,
                    Integer.toString(listing.price), Integer.toString(listing.guests), Integer.toString(listing.bedrooms), listing.imageUrl,
                    listing.description, Boolean.toString(listing.active)));
        }
        index = 0;
        for (Booking booking : STORE.bookings) {
            properties.setProperty("booking." + index++, encodeFields(booking.id, booking.userId, booking.listingId, booking.checkIn,
                    booking.checkOut, Integer.toString(booking.guests), Long.toString(booking.total), booking.status));
        }
        try {
            Files.createDirectories(DATA_FILE.getParent());
            Path temporary = DATA_FILE.resolveSibling(DATA_FILE.getFileName() + ".tmp");
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "FastRent local application data");
            }
            try {
                Files.move(temporary, DATA_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сохранить данные FastRent", exception);
        }
    }

    private static String encodeFields(String... values) {
        List<String> encoded = new ArrayList<>();
        for (String value : values) encoded.add(Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        return String.join("|", encoded);
    }

    private static List<String> decodeFields(String[] fields) {
        List<String> decoded = new ArrayList<>();
        for (String field : fields) decoded.add(new String(Base64.getUrlDecoder().decode(field), StandardCharsets.UTF_8));
        return decoded;
    }

    private static Listing findListing(String id) {
        return STORE.listings.stream().filter(listing -> listing.id.equals(id)).findFirst().orElse(null);
    }

    private static User findUserById(String id) {
        return STORE.users.values().stream().filter(user -> user.id.equals(id)).findFirst().orElse(null);
    }

    private static class Store {
        final Map<String, User> users = new HashMap<>();
        final List<Listing> listings = new ArrayList<>();
        final List<Booking> bookings = new ArrayList<>();
    }

    private static class User {
        final String id, email, name, passwordHash, role;
        User(String id, String email, String name, String passwordHash, String role) {
            this.id = id; this.email = email; this.name = name; this.passwordHash = passwordHash; this.role = role;
        }
    }

    private static class Listing {
        final String id, title, city, address, imageUrl, description;
        final int price, guests, bedrooms;
        boolean active;
        Listing(String id, String title, String city, String address, int price, int guests, int bedrooms,
                String imageUrl, String description, boolean active) {
            this.id = id; this.title = title; this.city = city; this.address = address; this.price = price;
            this.guests = guests; this.bedrooms = bedrooms; this.imageUrl = imageUrl; this.description = description; this.active = active;
        }
    }

    private static class Booking {
        final String id, userId, listingId, checkIn, checkOut;
        final int guests;
        final long total;
        String status;
        Booking(String id, String userId, String listingId, String checkIn, String checkOut, int guests, long total, String status) {
            this.id = id; this.userId = userId; this.listingId = listingId; this.checkIn = checkIn; this.checkOut = checkOut;
            this.guests = guests; this.total = total; this.status = status;
        }
    }

    private record Session(String userId, long expiresAt) { }

    private static class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
}
