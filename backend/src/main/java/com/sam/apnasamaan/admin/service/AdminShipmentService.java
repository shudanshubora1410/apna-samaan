package com.sam.apnasamaan.admin.service;

import com.sam.apnasamaan.common.dto.PagedResponse;
import com.sam.apnasamaan.shipment.dto.ShipmentSummaryResponse;
import com.sam.apnasamaan.shipment.entity.Shipment;
import com.sam.apnasamaan.shipment.entity.ShipmentStatus;
import com.sam.apnasamaan.shipment.repository.ShipmentRepository;
import com.sam.apnasamaan.shipment.service.ShipmentMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class AdminShipmentService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ShipmentRepository shipmentRepository;
    private final ShipmentMapper shipmentMapper;

    public AdminShipmentService(ShipmentRepository shipmentRepository, ShipmentMapper shipmentMapper) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentMapper = shipmentMapper;
    }

    @Transactional(readOnly = true)
    public PagedResponse<ShipmentSummaryResponse> searchShipments(
            ShipmentStatus status,
            String search,
            int page,
            int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        String searchPattern = (search == null || search.isBlank())
                ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";

        Page<Shipment> result = shipmentRepository.searchShipmentsForAdmin(status, searchPattern, pageable);
        List<ShipmentSummaryResponse> content = result.getContent().stream()
                .map(shipmentMapper::toSummary)
                .toList();

        return new PagedResponse<>(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isLast()
        );
    }
}
