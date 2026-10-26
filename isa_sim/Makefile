# Builds the ISA simulator: isa_sim/isa_sim.
# run_isa_sim.sh calls "make -C isa_sim" before every run, so a run always
# uses a binary built from the current sources.

CXX      ?= g++
# Kept separate from CXXFLAGS so that overriding CXXFLAGS cannot drop it.
CXXSTD   := -std=c++17
CXXFLAGS ?= -O2 -Wall -Wextra

SRCS := $(wildcard src/*.cpp)
HDRS := $(wildcard src/*.h)
OBJS := $(patsubst src/%.cpp,build/%.o,$(SRCS))

isa_sim: $(OBJS)
	$(CXX) $(CXXSTD) $(CXXFLAGS) -o $@ $(OBJS)

# Every object depends on every header: simple, and the build is small.
build/%.o: src/%.cpp $(HDRS) | build
	$(CXX) $(CXXSTD) $(CXXFLAGS) -c -o $@ $<

build:
	mkdir -p build

clean:
	rm -rf build isa_sim

.PHONY: clean
