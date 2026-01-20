Actions:
  - Identifier: BuildCAS
    Name: Build CAS with Corretto 21
    Configuration:
      Container:
        Image: "CodeCatalystLinux_x86_64:2024_03"
    Steps:
      - Name: Install Corretto 21
        Run: |
          sudo rpm --import https://yum.corretto.aws/corretto.key
          sudo curl -L -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
          sudo yum install -y java-21-amazon-corretto-devel

          # Derive JAVA_HOME from default install path
          export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto.x86_64
          if [ ! -d "$JAVA_HOME" ]; then
            # Fallback if arch suffix differs
            export JAVA_HOME=$(dirname "$(readlink -f /etc/alternatives/java_sdk_21)")
          fi
          export PATH=$JAVA_HOME/bin:$PATH

          java -version
      - Name: Build CAS
        Run: |
          java -version
          ./gradlew clean build --no-daemon --stacktrace
