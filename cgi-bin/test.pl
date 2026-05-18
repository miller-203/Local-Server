#!/usr/bin/env perl
use strict;
use warnings;

my $body = do { local $/; <STDIN> };

print "Content-Type: application/json\r\n\r\n";
print "{";
print "\"handler\":\"perl\",";
print "\"method\":\"" . json_escape($ENV{REQUEST_METHOD} // "") . "\",";
print "\"path_info\":\"" . json_escape($ENV{PATH_INFO} // "") . "\",";
print "\"query_string\":\"" . json_escape($ENV{QUERY_STRING} // "") . "\",";
print "\"content_length\":" . length($body) . ",";
print "\"body\":\"" . json_escape($body) . "\"";
print "}";

sub json_escape {
    my ($value) = @_;
    $value =~ s/\\/\\\\/g;
    $value =~ s/"/\\"/g;
    $value =~ s/\r/\\r/g;
    $value =~ s/\n/\\n/g;
    return $value;
}
