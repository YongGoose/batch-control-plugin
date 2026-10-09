package io.jenkins.plugins.batchcontrol.poc.multipart;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** PoC-6 question 5: calls the container's {@code getParts()} before Stapler, and records what happened. */
final class Poc6GetPartsProbe implements Filter {

    volatile String containerRequestClass;
    volatile String result;
    final List<String> partNames = new ArrayList<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest req && PocRepeatedFieldsFilter.inScope(req)) {
            ServletRequest inner = request;
            while (inner instanceof ServletRequestWrapper w) {
                inner = w.getRequest();
            }
            containerRequestClass = inner.getClass().getName();
            try {
                Collection<Part> parts = req.getParts();
                for (Part p : parts) {
                    partNames.add(p.getName());
                }
                result = "returned " + parts.size() + " parts";
            } catch (Exception e) {
                result = e.getClass().getName() + ": " + e.getMessage();
            }
        }
        chain.doFilter(request, response);
    }
}
