package studyroom;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import studyroom.booking.BookingRequests;
import studyroom.credit.CreditRequests;
import studyroom.feedback.FeedbackRequests;
import studyroom.platform.LoginRequest;
import studyroom.resource.ResourceRequests;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 29, 9, 0);
    private static final LocalDateTime END = START.plusHours(1);

    @AfterAll
    static void closeValidator() {
        FACTORY.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"login", "booking", "complaint", "reply", "review",
            "bookingReason", "blacklist", "seat", "user"})
    void requestFieldContract(String scenario, Object request, List<String> invalidFields) {
        assertThat(FACTORY.getValidator().validate(request))
            .extracting(violation -> violation.getPropertyPath().toString())
            .containsOnlyElementsOf(invalidFields);
    }

    static Stream<Arguments> login() {
        return Stream.of(
            arguments("login accepts credentials", new LoginRequest("alice", "secret")),
            arguments("login rejects missing username", new LoginRequest(null, "secret"), "username"),
            arguments("login rejects blank username", new LoginRequest(" ", "secret"), "username"),
            arguments("login rejects missing password", new LoginRequest("alice", null), "password"),
            arguments("login rejects blank password", new LoginRequest("alice", " "), "password"),
            arguments("login reports both missing fields", new LoginRequest(null, null), "username", "password"));
    }

    static Stream<Arguments> booking() {
        return Stream.of(
            arguments("booking accepts first positive seat ID", new BookingRequests.Booking(1, START, END)),
            arguments("booking rejects zero seat ID", new BookingRequests.Booking(0, START, END), "seatId"),
            arguments("booking rejects negative seat ID", new BookingRequests.Booking(-1, START, END), "seatId"),
            arguments("booking requires start time", new BookingRequests.Booking(1, null, END), "startAt"),
            arguments("booking requires end time", new BookingRequests.Booking(1, START, null), "endAt"),
            arguments("booking reports both missing times", new BookingRequests.Booking(1, null, null), "startAt", "endAt"));
    }

    static Stream<Arguments> complaint() {
        return Stream.of(
            arguments("complaint may omit reservation", new FeedbackRequests.Complaint(null, "topic", "body")),
            arguments("complaint may link reservation", new FeedbackRequests.Complaint(1L, "topic", "body")),
            arguments("complaint topic accepts 80 characters", new FeedbackRequests.Complaint(null, text(80), "body")),
            arguments("complaint topic rejects 81 characters", new FeedbackRequests.Complaint(null, text(81), "body"), "topic"),
            arguments("complaint topic rejects null", new FeedbackRequests.Complaint(null, null, "body"), "topic"),
            arguments("complaint topic rejects whitespace", new FeedbackRequests.Complaint(null, " ", "body"), "topic"),
            arguments("complaint body accepts 2000 characters", new FeedbackRequests.Complaint(null, "topic", text(2000))),
            arguments("complaint body rejects 2001 characters", new FeedbackRequests.Complaint(null, "topic", text(2001)), "content"),
            arguments("complaint body rejects null", new FeedbackRequests.Complaint(null, "topic", null), "content"),
            arguments("complaint body rejects whitespace", new FeedbackRequests.Complaint(null, "topic", " "), "content"));
    }

    static Stream<Arguments> reply() {
        return Stream.of(
            arguments("reply accepts PENDING target", new FeedbackRequests.Reply("PENDING", "body", "PENDING")),
            arguments("reply accepts PROCESSING target", new FeedbackRequests.Reply("PROCESSING", "body", "PENDING")),
            arguments("reply accepts RESOLVED target", new FeedbackRequests.Reply("RESOLVED", "body", "PROCESSING")),
            arguments("reply rejects withdrawal as a target", new FeedbackRequests.Reply("WITHDRAWN", "body", "PENDING"), "status"),
            arguments("reply rejects lowercase target", new FeedbackRequests.Reply("resolved", "body", "PENDING"), "status"),
            arguments("reply requires target status", new FeedbackRequests.Reply(null, "body", "PENDING"), "status"),
            arguments("reply requires expected status", new FeedbackRequests.Reply("RESOLVED", "body", null), "expectedStatus"),
            arguments("reply rejects blank expected status", new FeedbackRequests.Reply("RESOLVED", "body", " "), "expectedStatus"),
            arguments("reply accepts 2000 characters", new FeedbackRequests.Reply("RESOLVED", text(2000), "PENDING")),
            arguments("reply rejects 2001 characters", new FeedbackRequests.Reply("RESOLVED", text(2001), "PENDING"), "reply"),
            arguments("reply rejects blank content", new FeedbackRequests.Reply("RESOLVED", " ", "PENDING"), "reply"));
    }

    static Stream<Arguments> review() {
        return Stream.of(
            arguments("review accepts rating one", new FeedbackRequests.Review(1, 1, "body")),
            arguments("review accepts rating five", new FeedbackRequests.Review(1, 5, "body")),
            arguments("review rejects rating zero", new FeedbackRequests.Review(1, 0, "body"), "rating"),
            arguments("review rejects rating six", new FeedbackRequests.Review(1, 6, "body"), "rating"),
            arguments("review rejects zero reservation ID", new FeedbackRequests.Review(0, 3, "body"), "reservationId"),
            arguments("review rejects negative reservation ID", new FeedbackRequests.Review(-1, 3, "body"), "reservationId"),
            arguments("review accepts 1000 characters", new FeedbackRequests.Review(1, 3, text(1000))),
            arguments("review rejects 1001 characters", new FeedbackRequests.Review(1, 3, text(1001)), "content"),
            arguments("review requires content", new FeedbackRequests.Review(1, 3, null), "content"),
            arguments("review rejects blank content", new FeedbackRequests.Review(1, 3, " "), "content"));
    }

    static Stream<Arguments> bookingReason() {
        return Stream.of(
            arguments("booking reason accepts 300 characters", new BookingRequests.Reason(text(300))),
            arguments("booking reason rejects 301 characters", new BookingRequests.Reason(text(301)), "reason"),
            arguments("booking reason requires content", new BookingRequests.Reason(null), "reason"),
            arguments("manual violation accepts valid input", new BookingRequests.Violation(1, "reason")),
            arguments("manual violation rejects zero reservation ID", new BookingRequests.Violation(0, "reason"), "reservationId"),
            arguments("manual violation rejects blank reason", new BookingRequests.Violation(1, " "), "reason"));
    }

    static Stream<Arguments> blacklist() {
        return Stream.of(
            arguments("blacklist accepts one day", new CreditRequests.Blacklist(1, "reason")),
            arguments("blacklist accepts 365 days", new CreditRequests.Blacklist(365, "reason")),
            arguments("blacklist rejects zero days", new CreditRequests.Blacklist(0, "reason"), "days"),
            arguments("blacklist rejects 366 days", new CreditRequests.Blacklist(366, "reason"), "days"),
            arguments("blacklist requires reason", new CreditRequests.Blacklist(7, null), "reason"),
            arguments("blacklist accepts 300-character reason", new CreditRequests.Blacklist(7, text(300))),
            arguments("blacklist rejects 301-character reason", new CreditRequests.Blacklist(7, text(301)), "reason"));
    }

    static Stream<Arguments> seat() {
        return Stream.of(
            arguments("seat accepts first positive room ID", new ResourceRequests.Seat(1, "A1", false, true)),
            arguments("seat rejects zero room ID", new ResourceRequests.Seat(0, "A1", false, true), "roomId"),
            arguments("seat rejects negative room ID", new ResourceRequests.Seat(-1, "A1", false, true), "roomId"),
            arguments("seat accepts 20-character code", new ResourceRequests.Seat(1, text(20), false, true)),
            arguments("seat rejects 21-character code", new ResourceRequests.Seat(1, text(21), false, true), "code"),
            arguments("seat requires code", new ResourceRequests.Seat(1, null, false, true), "code"),
            arguments("seat rejects blank code", new ResourceRequests.Seat(1, " ", false, true), "code"));
    }

    static Stream<Arguments> user() {
        return Stream.of(
            arguments("username accepts four allowed characters", new ResourceRequests.UserCreate("A_z0", "name", text(8))),
            arguments("username accepts 32 characters", new ResourceRequests.UserCreate(text(32), "name", text(8))),
            arguments("username rejects three characters", new ResourceRequests.UserCreate(text(3), "name", text(8)), "username"),
            arguments("username rejects 33 characters", new ResourceRequests.UserCreate(text(33), "name", text(8)), "username"),
            arguments("username rejects punctuation", new ResourceRequests.UserCreate("a-bc", "name", text(8)), "username"),
            arguments("password accepts 72 characters", new ResourceRequests.UserCreate("user", "name", text(72))),
            arguments("password rejects 73 characters", new ResourceRequests.UserCreate("user", "name", text(73)), "password"),
            arguments("password rejects seven characters", new ResourceRequests.UserCreate("user", "name", text(7)), "password"),
            arguments("display name accepts 40 characters", new ResourceRequests.UserUpdate(text(40), true)),
            arguments("display name rejects 41 characters", new ResourceRequests.UserUpdate(text(41), true), "displayName"),
            arguments("display name rejects whitespace", new ResourceRequests.UserUpdate(" ", true), "displayName"),
            arguments("display name rejects null", new ResourceRequests.UserUpdate(null, true), "displayName"));
    }

    private static String text(int length) {
        return "x".repeat(length);
    }

    private static Arguments arguments(String scenario, Object request, String... fields) {
        return Arguments.of(scenario, request, List.of(fields));
    }
}
