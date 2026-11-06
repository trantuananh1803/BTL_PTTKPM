package vn.edu.phenikaa.alumni;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlumniManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlumniManagementApplication.class, args);
    }
}
