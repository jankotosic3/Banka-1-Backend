package com.banka1.clientService.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP filter koji primenjuje ogranicenje broja zahteva (rate limiting) po IP adresi.
 * Stiti osetljive endpoint-e od prekomjernog koriscenja.
 * Parametri {@code maxRequests} i {@code windowMs} se injektuju iz konfiguracije
 * kako bi se mogli menjati bez ponovnog build-a.
 * Filter se primenjuje samo na POST zahteve na putanjama registrovanim u {@link RateLimitConfig}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** Maksimalni broj zahteva po IP adresi unutar vremenskog prozora. */
    private final int maxRequests;

    /** Trajanje vremenskog prozora u milisekundama. */
    private final long windowMs;

    /**
     * Mapa koja cuva vremenske markice zahteva po IP adresi.
     * Kljuc je IP adresa, vrednost je deque vremenskih markica unutar tekuceg prozora.
     */
    private final ConcurrentHashMap<String, Deque<Long>> requestMap = new ConcurrentHashMap<>();

    public RateLimitFilter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    /**
     * Proverava rate limit za svaki dolazeci POST zahtev.
     * Zahtevi koji nisu POST se propustaju bez provere. Prekoracenje limita rezultuje HTTP statusom 429.
     *
     * @param request     dolazeci HTTP zahtev
     * @param response    HTTP odgovor
     * @param filterChain lanac filtera
     * @throws ServletException ako dodje do greske u obradi filtera
     * @throws IOException      ako dodje do I/O greske
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = getClientIp(request);
        long now = System.currentTimeMillis();

        Deque<Long> timestamps = requestMap.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                response.setStatus(429);
                response.getWriter().write("Too many requests");
                return;
            }
            timestamps.addLast(now);
        }

        // Remove entries whose deques are empty (all timestamps expired) to prevent memory leak
        requestMap.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty();
            }
        });

        filterChain.doFilter(request, response);
    }

    /**
     * Odredjuje IP adresu klijenta uzimajuci u obzir proxy zaglavlje {@code X-Forwarded-For}.
     * Ako zaglavlje nije prisutno, koristi se direktna adresa veze.
     *
     * @param request HTTP zahtev iz kog se cita IP adresa
     * @return IP adresa klijenta
     */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
