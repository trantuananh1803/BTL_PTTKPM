-- Keep the existing catalog IDs and profile foreign keys; align only the
-- development catalog codes/names with the requested CNTT / ICT1-ICT5 values.
UPDATE faculties
SET code = 'CNTT',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE code = '1';

UPDATE majors
SET name = CASE code
        WHEN 'N3' THEN CONVERT(0x43c3b46e67206e6768e1bb87205468c3b46e672074696e USING utf8mb4)
        WHEN 'N4' THEN CONVERT(0x4be1bbb920746875e1baad74205068e1baa76e206de1bb816d USING utf8mb4)
        WHEN 'N5' THEN CONVERT(0x4b686f612068e1bb8d63204dc3a1792074c3ad6e68202841492026204461746120536369656e636529 USING utf8mb4)
        WHEN 'N8' THEN CONVERT(0x416e20746fc3a06e205468c3b46e672074696e USING utf8mb4)
        WHEN 'N1' THEN CONVERT(0x5472c3ad207475e1bb87204e68c3a26e2074e1baa16f USING utf8mb4)
    END,
    code = CASE code
        WHEN 'N3' THEN 'ICT1'
        WHEN 'N4' THEN 'ICT2'
        WHEN 'N5' THEN 'ICT3'
        WHEN 'N8' THEN 'ICT4'
        WHEN 'N1' THEN 'ICT5'
    END,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE faculty_id = (SELECT id FROM faculties WHERE code = 'CNTT')
  AND code IN ('N1', 'N3', 'N4', 'N5', 'N8');
