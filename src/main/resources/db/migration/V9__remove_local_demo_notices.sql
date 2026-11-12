-- Remove the six local development notices that are no longer part of the demo dataset.
DELETE FROM bookmarks
 WHERE source = 'MANUAL'
   AND notice_id IN (
       SELECT id FROM notices WHERE title IN (
           '2026 가을 대동제 부스 운영팀 모집',
           '[AI융합학부] 간식사업 신청자 모집',
           '[중요] 중간고사 기간 도서관 운영 변경',
           '2학기 학생회비 사용 내역 공개',
           '제휴 카페 학생 할인 안내',
           '[예약] 학과 MT 참가 신청 안내'
       )
   );

DELETE FROM notifications
 WHERE source = 'MANUAL'
   AND notice_id IN (
       SELECT id FROM notices WHERE title IN (
           '2026 가을 대동제 부스 운영팀 모집',
           '[AI융합학부] 간식사업 신청자 모집',
           '[중요] 중간고사 기간 도서관 운영 변경',
           '2학기 학생회비 사용 내역 공개',
           '제휴 카페 학생 할인 안내',
           '[예약] 학과 MT 참가 신청 안내'
       )
   );

DELETE FROM notices
 WHERE title IN (
     '2026 가을 대동제 부스 운영팀 모집',
     '[AI융합학부] 간식사업 신청자 모집',
     '[중요] 중간고사 기간 도서관 운영 변경',
     '2학기 학생회비 사용 내역 공개',
     '제휴 카페 학생 할인 안내',
     '[예약] 학과 MT 참가 신청 안내'
 );
