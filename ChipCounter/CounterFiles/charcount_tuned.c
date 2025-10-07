#include "rocc.h"
#include <stdio.h>

char string[64] __attribute__ ((aligned (64))) = "The quick brown fox jumped over the lazy dog";

static inline unsigned long count_chars_tuned(char *start, char needle, unsigned int chunk_size)
{
	unsigned long count;
	// Pack needle (lower 8 bits) and chunk_size (bits 11:8) into rs2_value
	unsigned long rs2_value = ((unsigned long)needle) | (((unsigned long)chunk_size & 0xF) << 8);
	
	asm volatile ("fence");
    printf("Counting '%c' with chunk size %u starting at address %p\n", needle, chunk_size, start);
    printf("\n");
	printf("DEBUG: rs2_value = 0x%lx\n", rs2_value);
    ROCC_INSTRUCTION_DSS(2, count, start, rs2_value, 0);
    printf("Finished count result: %lu\n", count);
	return count;
}

int main(void)
{
    printf("Full string: \"%s\"\n", string);
    
    // Count 'o' characters in full string (should be 4)
    unsigned long count = count_chars_tuned(string, 'o', 4);
    printf("Count of 'o': %lu\n", count);
    printf("Expected: 4\n");
    
    if (count == 4) {
        printf("TEST PASSED: Basic functionality works!\n");
        return 0;
    } else {
        printf("TEST FAILED: Expected 4, got %lu\n", count);
        return 1;
    }
}
